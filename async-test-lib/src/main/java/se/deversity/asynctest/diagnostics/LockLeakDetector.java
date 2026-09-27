package se.deversity.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Detects lock leak patterns where locks are acquired but never released.
 * 
 * Common lock leak issues detected:
 * - Lock acquired but never released (missing unlock in finally block)
 * - Lock held across exception boundaries without proper cleanup
 * - ReentrantLock used without try-finally pattern
 * - Lock held for excessive duration (potential deadlock precursor)
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectLockLeaks = true)
 * void testLockUsage() {
 *     ReentrantLock lock = new ReentrantLock();
 *     AsyncTestContext.lockLeakMonitor()
 *         .registerLock(lock, "resource-lock");
 *     
 *     lock.lock();
 *     AsyncTestContext.lockLeakMonitor()
 *         .recordLockAcquired(lock, "resource-lock");
 *     try {
 *         // critical section
 *     } finally {
 *         lock.unlock();
 *         AsyncTestContext.lockLeakMonitor()
 *             .recordLockReleased(lock, "resource-lock");
 *     }
 * }
 * }</pre>
 */
public class LockLeakDetector {

    private static class LockState {
        final String name;
        final AtomicInteger acquireCount = new AtomicInteger(0);
        final AtomicInteger releaseCount = new AtomicInteger(0);
        final Set<Long> acquiringThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> releasingThreads = ConcurrentHashMap.newKeySet();
        final Map<Long, Long> threadAcquireTime = new ConcurrentHashMap<>();
        final AtomicInteger maxHoldTimeMs = new AtomicInteger(0);
        volatile boolean currentlyHeld = false;
        volatile @Nullable Long lastAcquireTime = null;
        /**
         * The thread last recorded acquiring a {@code ReentrantLock} while holding it, so that
         * analysis can ask that thread, rather than a name, whether it is done with the lock.
         * Held for the detector's lifetime, which is one run.
         */
        volatile @Nullable Thread holder;

        LockState(Lock lock, String name) {
            this.name = name != null ? name : "lock@" + System.identityHashCode(lock);
        }
    }

    private final Map<IdentityKey, LockState> locks = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a Lock for monitoring.
     * 
     * @param lock the Lock to monitor
     * @param name a descriptive name for reporting
     */
    public void registerLock(Lock lock, String name) {
        if (!enabled || lock == null) {
            return;
        }
        // Idempotent on purpose. The documented usage (see the class Javadoc) calls this from
        // inside the @AsyncTest body, which the runner executes threads × invocations times
        // against the same lock. A put() would install a fresh LockState each time, wiping the
        // acquire/release counts — so an acquire leaked by an earlier invocation would be
        // erased before analysis ever saw it.
        locks.computeIfAbsent(new IdentityKey(lock), ignored -> new LockState(lock, name));
    }

    /**
     * Record that a lock was acquired.
     * 
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param name the lock name (should match registration)
     */
    public void recordLockAcquired(Lock lock, String name) {
        if (!enabled || lock == null) {
            return;
        }
        // Auto-register atomically: a get-then-put lets two threads that both miss each build
        // and install a LockState, and the loser's increments then land on an orphaned object
        // that analysis never sees.
        LockState state = locks.computeIfAbsent(new IdentityKey(lock),
                                                ignored -> new LockState(lock, name));
        state.acquireCount.incrementAndGet();
        state.acquiringThreads.add(Thread.currentThread().threadId());
        state.currentlyHeld = true;
        if (lock instanceof ReentrantLock reentrant && reentrant.isHeldByCurrentThread()) {
            state.holder = Thread.currentThread();
        }
        long now = System.currentTimeMillis();
        state.lastAcquireTime = now;
        state.threadAcquireTime.put(Thread.currentThread().threadId(), now);
    }

    /**
     * Record that a lock was released.
     * 
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param name the lock name (should match registration)
     */
    public void recordLockReleased(Lock lock, String name) {
        if (!enabled || lock == null) {
            return;
        }
        IdentityKey id = new IdentityKey(lock);
        LockState state = locks.get(id);
        if (state == null) {
            // Auto-register atomically. get-then-put let two threads racing on the same lock
            // each keep a private state whose releasingThreads held one id, so cross-thread
            // acquire/release imbalance became invisible under contention.
            state = locks.computeIfAbsent(id, k -> new LockState(lock, name));
        }
        state.releaseCount.incrementAndGet();
        state.releasingThreads.add(Thread.currentThread().threadId());
        state.currentlyHeld = false;

        // Calculate hold time
        Long acquireTime = state.threadAcquireTime.remove(Thread.currentThread().threadId());
        if (acquireTime != null) {
            int holdTimeMs = (int) (System.currentTimeMillis() - acquireTime);
            state.maxHoldTimeMs.updateAndGet(max -> Math.max(max, holdTimeMs));
        }
    }

    /**
     * Analyze lock usage for leak patterns.
     * 
     * @return a report of detected issues
     */
    public LockLeakReport analyze() {
        LockLeakReport report = new LockLeakReport();
        report.enabled = enabled;
        Set<Thread> platformThreads = null;

        for (Map.Entry<IdentityKey, LockState> entry : locks.entrySet()) {
            LockState state = entry.getValue();
            int acquires = state.acquireCount.get();
            int releases = state.releaseCount.get();
            boolean leaked = acquires > releases;
            // The lock is asked only about a leak the counts already show, so a confirmation can
            // raise a finding's grade and never make one.
            String confirmed = null;
            if ((leaked || state.currentlyHeld) && entry.getKey().referent() instanceof ReentrantLock lock) {
                if (platformThreads == null) {
                    platformThreads = Thread.getAllStackTraces().keySet();
                }
                confirmed = confirmedHold(lock, state.holder, platformThreads);
            }

            // Check for lock leaks (more acquires than releases)
            if (leaked) {
                String leak = String.format(
                    "%s: acquired %d times but released only %d times (%d potential leaks)",
                    state.name, acquires, releases, acquires - releases);
                report.lockLeaks.add(confirmed == null ? leak : leak + confirmed);
                if (confirmed != null) {
                    report.observed.add(leak + confirmed);
                }
            }

            // Check for currently held locks at analysis time
            if (state.currentlyHeld) {
                long holdTimeMs = state.lastAcquireTime != null
                    ? System.currentTimeMillis() - state.lastAcquireTime
                    : 0;
                String held = String.format(
                    "%s: lock is currently held (last acquired %dms ago)",
                    state.name, holdTimeMs);
                report.heldLocks.add(confirmed == null ? held : held + confirmed);
                if (confirmed != null) {
                    report.observed.add(held + confirmed);
                }
            }

            // Check for excessive hold times
            if (state.maxHoldTimeMs.get() > 5000) { // More than 5 seconds
                report.excessiveHoldTimes.add(String.format(
                    "%s: lock held for up to %dms (potential deadlock precursor)",
                    state.name, state.maxHoldTimeMs.get()));
            }

            // Track thread participation
            if (!state.acquiringThreads.isEmpty()) {
                report.threadActivity.add(String.format(
                    "%s: %d threads acquired, %d threads released, max hold: %dms",
                    state.name,
                    state.acquiringThreads.size(),
                    state.releasingThreads.size(),
                    state.maxHoldTimeMs.get()));
            }
        }

        return report;
    }

    /**
     * {@return what the lock itself says about a leak the counts show, as a suffix for the finding,
     * or {@code null} when it does not confirm it}
     *
     * <p>A hold is confirmed when {@link ReentrantLock#isLocked()} is still true, the analysing
     * thread is not the holder, and the holder the lock names has stopped working: it has ended, or
     * it waits in its pool for the next task. That is {@code ReentrantLockDetector}'s test for a
     * held lock, asked through the same code (#837). A holder still running may yet release the
     * lock, and a lock whose recorded counts disagree with it (the acquire and release recorded
     * in different places) is not held at all; both keep the recorded finding at its recorded
     * grade.
     *
     * <p>The lock names its holder only by name, so the hold is confirmed only when that name can
     * be nobody but the thread this detector recorded acquiring it (#843). An empty name is every
     * unnamed virtual thread's, a name another live platform thread also carries is ambiguous, and
     * a name the recorded thread does not carry belongs to a thread that never recorded: finding
     * no live platform thread of that name says nothing about a virtual one, which no scan can
     * list. Each stays at its recorded grade; {@code ReentrantLockDetector}'s own finding still
     * reads the name alone. What remains is a virtual thread deliberately given the recorded
     * thread's non-empty name.
     */
    private static @Nullable String confirmedHold(ReentrantLock lock, @Nullable Thread recorded,
                                                  Set<Thread> platformThreads) {
        if (recorded == null || !lock.isLocked() || lock.isHeldByCurrentThread()) {
            return null;
        }
        String holderName = ReentrantLockDetector.holderNameOf(lock);
        if (holderName == null || holderName.isEmpty() || !holderName.equals(recorded.getName())
                || anotherLiveThreadIsNamed(holderName, recorded, platformThreads)) {
            return null;
        }
        ReentrantLockDetector.HolderState state = ReentrantLockDetector.holderState(
                holderName, recorded, Set.of(), platformThreads);
        if (state == ReentrantLockDetector.HolderState.WORKING) {
            return null;
        }
        return String.format(" - ReentrantLock.isLocked() confirms it: held by '%s', %s",
                holderName, state == ReentrantLockDetector.HolderState.IDLE
                        ? "now idle in its pool" : "which has finished");
    }

    /** {@return whether a live platform thread other than {@code recorded} is also named {@code name}} */
    private static boolean anotherLiveThreadIsNamed(String name, Thread recorded, Set<Thread> platformThreads) {
        for (Thread thread : platformThreads) {
            if (!thread.equals(recorded) && thread.isAlive() && name.equals(thread.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Report class for lock leak analysis.
     */
    public static class LockLeakReport implements GradedFindings {
        private boolean enabled = true;
        final java.util.List<String> lockLeaks = new java.util.ArrayList<>();
        final java.util.List<String> heldLocks = new java.util.ArrayList<>();
        final java.util.List<String> excessiveHoldTimes = new java.util.ArrayList<>();
        /**
         * One line per lock object, named but not keyed by the name: two locks may
         * share a name, and filed under it the second one's line overwrote the first's (#789).
         */
        final java.util.List<String> threadActivity = new java.util.ArrayList<>();
        /** The leak and held-lock findings the lock itself confirmed; see {@link #grades()}. */
        final Set<String> observed = new java.util.HashSet<>();

        /**
         * Check if any issues were detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
        public boolean hasIssues() {
            return !lockLeaks.isEmpty() || !heldLocks.isEmpty() || !excessiveHoldTimes.isEmpty();
        }

        /**
         * One grade per finding, set by the path that produced it (#754).
         *
         * <p>An acquire with no matching release and a lock still marked held at analysis are
         * arithmetic over the acquires and releases the test recorded: true as recorded, so each is
         * a {@link TrustTier#FACT} on {@link DetectorTrust.Evidence#ASSERTED} evidence. A hold over
         * five seconds is a threshold, and a slow critical section that does release is not a leak,
         * so it stays a {@link TrustTier#PROMPT}. Before this the whole detector was rated by that
         * threshold. Every grade keeps the severity the gate has always read for this report.
         *
         * <p>Since #837 a {@code ReentrantLock} is also asked at analysis. When it is still locked
         * and the holder it names, which must be the thread recorded acquiring it (#843), has ended
         * or waits idle in its pool, the leak is what the JVM says rather than what was recorded,
         * and both of that lock's findings are a
         * {@link TrustTier#VERDICT} on {@link DetectorTrust.Evidence#OBSERVED} evidence. The
         * correct twin, {@code unlock()} in a {@code finally}, leaves the lock free and the counts
         * balanced, and draws neither.
         */
        @Override
        public java.util.List<GradedFindings.Grade> grades() {
            if (!hasIssues()) {
                return java.util.List.of();
            }
            IssueSeverity severity = DetectorDefaultSeverity.of(LockLeakDetector.class.getSimpleName(), toString());
            java.util.List<GradedFindings.Grade> out = new java.util.ArrayList<>();
            for (String leak : lockLeaks) {
                out.add(gradeOf(severity, leak));
            }
            for (String held : heldLocks) {
                out.add(gradeOf(severity, held));
            }
            for (String slow : excessiveHoldTimes) {
                out.add(new GradedFindings.Grade(severity, TrustTier.PROMPT, slow, DetectorTrust.Evidence.HEURISTIC));
            }
            return java.util.List.copyOf(out);
        }

        /** {@return a leak or held-lock finding's grade: observed if the lock confirmed it, else as recorded} */
        private GradedFindings.Grade gradeOf(IssueSeverity severity, String finding) {
            return observed.contains(finding)
                    ? new GradedFindings.Grade(severity, TrustTier.VERDICT, finding, DetectorTrust.Evidence.OBSERVED)
                    : new GradedFindings.Grade(severity, TrustTier.FACT, finding, DetectorTrust.Evidence.ASSERTED);
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "LockLeakReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("LOCK LEAK ISSUES DETECTED:\n");

            if (!lockLeaks.isEmpty()) {
                sb.append("  Lock Leaks:\n");
                for (String leak : lockLeaks) {
                    sb.append("    - ").append(leak).append("\n");
                }
            }

            if (!heldLocks.isEmpty()) {
                sb.append("  Currently Held Locks:\n");
                for (String held : heldLocks) {
                    sb.append("    - ").append(held).append("\n");
                }
            }

            if (!excessiveHoldTimes.isEmpty()) {
                sb.append("  Excessive Hold Times:\n");
                for (String excessive : excessiveHoldTimes) {
                    sb.append("    - ").append(excessive).append("\n");
                }
            }

            if (!threadActivity.isEmpty()) {
                sb.append("  Thread Activity:\n");
                for (String activity : threadActivity) {
                    sb.append("    - ").append(activity).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("""
  Why: An unreleased lock blocks every other thread that tries to acquire it — they wait indefinitely,
       causing the test or application to hang. Even a single missed unlock in an exception path is enough
       to starve all contenders for that lock.
  Fix:
    - Always wrap lock usage in try/finally: lock.lock(); try { ... } finally { lock.unlock(); }
    - For scoped locking, use a helper: try (var l = new AutoUnlock(lock)) { ... }
    - Prefer synchronized blocks for simple cases — the JVM guarantees unlock on exit\
""");
            return sb.toString();
        }
    }
}
