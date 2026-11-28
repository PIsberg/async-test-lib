package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
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
 * @AsyncTest(threads = 4, includes = DetectorType.LOCK_LEAKS)
 * void testLockUsage() {
 *     ReentrantLock lock = new ReentrantLock();
 *     AsyncTestContext.lockLeakDetector()
 *         .registerLock(lock, "resource-lock");
 *     
 *     lock.lock();
 *     AsyncTestContext.lockLeakDetector()
 *         .recordLockAcquired(lock, "resource-lock");
 *     try {
 *         // critical section
 *     } finally {
 *         lock.unlock();
 *         AsyncTestContext.lockLeakDetector()
 *             .recordLockReleased(lock, "resource-lock");
 *     }
 * }
 * }</pre>
 */
public class LockLeakDetector {

    /** Labels for objects the test gave no name, numbered per kind within this detector (#860). */
    private final UnnamedLabels unnamedLabels = new UnnamedLabels();

    /**
     * One thread's hold of one lock, kept at depth zero between holds so the record path allocates
     * only on a thread's first acquire. Touched only by that thread, so it needs no synchronization.
     */
    private static final class Hold {
        long since;
        int depth;
    }

    private static class LockState {
        final String name;
        final AtomicInteger acquireCount = new AtomicInteger(0);
        final AtomicInteger releaseCount = new AtomicInteger(0);
        final Set<Long> acquiringThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> releasingThreads = ConcurrentHashMap.newKeySet();
        /**
         * Each thread's current hold, timed from its outermost acquire to the release that ends it,
         * so a re-entered lock is timed as one hold rather than as its innermost section (#1505).
         */
        final Map<Long, Hold> threadHolds = new ConcurrentHashMap<>();
        final AtomicInteger maxHoldTimeMs = new AtomicInteger(0);
        volatile boolean currentlyHeld = false;
        volatile @Nullable Long lastAcquireTime = null;
        /**
         * The thread last recorded acquiring a {@code ReentrantLock} while holding it, so that
         * analysis can ask that thread, rather than a name, whether it is done with the lock.
         * Held for the detector's lifetime, which is one run.
         */
        volatile @Nullable Thread holder;
        /**
         * Every thread that registered or recorded against the lock, so that a live one carrying the
         * holder's name is seen even when it is virtual and no scan can list it (#855). Held for the
         * detector's lifetime, which is one run.
         */
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        /**
         * Acquires not yet released, per thread the run handed work to and that is not one of its
         * workers (#1339). Kept out of the counts above: such a thread may outlive the run, so its
         * hold is a finding only when the lock confirms it at analysis.
         */
        final Map<Thread, AtomicInteger> helperHolds = new ConcurrentHashMap<>();

        LockState(String name, UnnamedLabels labels) {
            this.name = name != null ? name : labels.next("lock");
        }
    }

    private final Map<IdentityKey, LockState> locks = new ConcurrentHashMap<>();

    /** A hold longer than this is reported as a deadlock precursor; a threshold, so graded a prompt. */
    private static final long EXCESSIVE_HOLD_MS = 5000;
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
        locks.computeIfAbsent(new IdentityKey(lock), ignored -> new LockState(name, unnamedLabels))
                .threads.add(Thread.currentThread());
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
                                                ignored -> new LockState(name, unnamedLabels));
        state.acquireCount.incrementAndGet();
        state.acquiringThreads.add(Thread.currentThread().threadId());
        state.threads.add(Thread.currentThread());
        state.currentlyHeld = true;
        if (lock instanceof ReentrantLock reentrant && reentrant.isHeldByCurrentThread()) {
            state.holder = Thread.currentThread();
        }
        long now = System.currentTimeMillis();
        state.lastAcquireTime = now;
        long threadId = Thread.currentThread().threadId();
        Hold hold = state.threadHolds.get(threadId);
        if (hold == null) {
            hold = state.threadHolds.computeIfAbsent(threadId, ignored -> new Hold());
        }
        // Recorded after the acquire, so a ReentrantLock that counts one hold says this is the
        // thread's outermost hold, whatever the recorded depth: a release it never recorded left
        // the depth stale, and every later hold went untimed (#1511). A real re-entry counts more.
        if (hold.depth > 0 && lock instanceof ReentrantLock reentrant
                && reentrant.isHeldByCurrentThread() && reentrant.getHoldCount() == 1) {
            hold.depth = 0;
        }
        if (hold.depth == 0) {
            hold.since = now;
        }
        hold.depth++;
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
            state = locks.computeIfAbsent(id, k -> new LockState(name, unnamedLabels));
        }
        state.releaseCount.incrementAndGet();
        state.releasingThreads.add(Thread.currentThread().threadId());
        state.threads.add(Thread.currentThread());
        state.currentlyHeld = false;

        // Time the hold when this release ends it; an inner release of a re-entered lock does not.
        long threadId = Thread.currentThread().threadId();
        Hold hold = state.threadHolds.get(threadId);
        if (hold != null && hold.depth > 0) {
            hold.depth--;
            if (hold.depth == 0) {
                int holdTimeMs = (int) (System.currentTimeMillis() - hold.since);
                state.maxHoldTimeMs.updateAndGet(max -> Math.max(max, holdTimeMs));
            }
        }
    }

    /**
     * Internal: an acquire by a thread the run handed work to, a started thread or a pool thread
     * running its task, that is not one of the run's workers (#1339). Never a finding alone: see
     * {@link #analyze()}.
     *
     * @param lock the lock acquired, tracked by identity
     * @param name the label to report it under
     * @since 1.14.3
     */
    public void recordHelperAcquired(Lock lock, String name) {
        if (!enabled || lock == null) {
            return;
        }
        LockState state = locks.computeIfAbsent(new IdentityKey(lock),
                                                ignored -> new LockState(name, unnamedLabels));
        Thread thread = Thread.currentThread();
        state.threads.add(thread);
        state.helperHolds.computeIfAbsent(thread, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Internal: the release half of {@link #recordHelperAcquired}. A release with no recorded
     * acquire, such as one whose acquire ran in unwoven code, is ignored.
     *
     * @param lock the lock released, tracked by identity
     * @since 1.14.3
     */
    public void recordHelperReleased(Lock lock) {
        if (!enabled || lock == null) {
            return;
        }
        LockState state = locks.get(new IdentityKey(lock));
        AtomicInteger holds = state == null ? null : state.helperHolds.get(Thread.currentThread());
        if (holds != null) {
            holds.updateAndGet(count -> Math.max(0, count - 1));
        }
    }

    /**
     * Analyze lock usage for leak patterns.
     *
     * <p>A hold recorded by {@link #recordHelperAcquired} is reported only for a
     * {@code ReentrantLock} that is still locked and names that thread as a holder that has ended
     * or idles in its pool, the confirmation a worker's leak is graded by (#837). A helper thread
     * still working may yet release the lock and is not the run's to judge (#1339).
     *
     * @return a report of detected issues
     */
    public LockLeakReport analyze() {
        LockLeakReport report = new LockLeakReport();
        report.enabled = enabled;
        PlatformThreads platformThreads = new PlatformThreads();
        for (Map.Entry<IdentityKey, LockState> entry : locks.entrySet()) {
            analyzeLock(entry.getKey().referent(), entry.getValue(), platformThreads, report);
        }
        addStructuredViolations(report);
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    /**
     * The live platform threads, listed at most once per analysis and only when a lock needs them:
     * {@link Thread#getAllStackTraces()} walks every stack in the JVM.
     */
    private static final class PlatformThreads {
        private @Nullable Set<Thread> threads;

        Set<Thread> get() {
            if (threads == null) {
                threads = Thread.getAllStackTraces().keySet();
            }
            return threads;
        }
    }

    /** Adds what one lock's record shows to {@code report}: each check below is independent. */
    private static void analyzeLock(@Nullable Object lock, LockState state, PlatformThreads platformThreads,
                                    LockLeakReport report) {
        int acquires = state.acquireCount.get();
        int releases = state.releaseCount.get();
        boolean leaked = acquires > releases;
        // The lock is asked only about a leak the counts already show, so a confirmation can
        // raise a finding's grade and never make one.
        String confirmed = (leaked || state.currentlyHeld) && lock instanceof ReentrantLock reentrant
                ? confirmedHold(reentrant, state.holder, platformThreads.get(), state.threads)
                : null;

        if (leaked) {
            report.lockLeaks.add(withConfirmation(report, String.format(
                    "%s: acquired %d times but released only %d times (%d potential leaks)",
                    state.name, acquires, releases, acquires - releases), confirmed));
        }
        if (state.currentlyHeld) {
            Long lastAcquire = state.lastAcquireTime;
            long holdTimeMs = lastAcquire != null ? System.currentTimeMillis() - lastAcquire : 0;
            report.heldLocks.add(withConfirmation(report, String.format(
                    "%s: lock is currently held (last acquired %dms ago)", state.name, holdTimeMs), confirmed));
        }
        if (!leaked && !state.currentlyHeld && lock instanceof ReentrantLock reentrant) {
            reportHelperHold(reentrant, state, platformThreads, report);
        }
        if (state.maxHoldTimeMs.get() > EXCESSIVE_HOLD_MS) {
            report.excessiveHoldTimes.add(String.format(
                    "%s: lock held for up to %dms (potential deadlock precursor)",
                    state.name, state.maxHoldTimeMs.get()));
        }
        if (!state.acquiringThreads.isEmpty()) {
            report.threadActivity.add(String.format(
                    "%s: %d threads acquired, %d threads released, max hold: %dms",
                    state.name, state.acquiringThreads.size(), state.releasingThreads.size(),
                    state.maxHoldTimeMs.get()));
        }
    }

    /**
     * {@return {@code finding} with the lock's confirmation appended, which also marks it observed in
     * {@code report}, or {@code finding} as recorded when the lock did not confirm it}
     */
    private static String withConfirmation(LockLeakReport report, String finding, @Nullable String confirmed) {
        if (confirmed == null) {
            return finding;
        }
        report.observed.add(finding + confirmed);
        return finding + confirmed;
    }

    /**
     * Reports the first hold of a thread the run handed work to (#1339) that the lock confirms. An
     * unconfirmed one may still be released by a helper that is working, and is not the run's to judge.
     */
    private static void reportHelperHold(ReentrantLock lock, LockState state, PlatformThreads platformThreads,
                                         LockLeakReport report) {
        for (Map.Entry<Thread, AtomicInteger> hold : state.helperHolds.entrySet()) {
            if (hold.getValue().get() <= 0) {
                continue;
            }
            String byLock = confirmedHold(lock, hold.getKey(), platformThreads.get(), state.threads);
            if (byLock != null) {
                String leak = withConfirmation(report, String.format(
                        "%s: acquired by a thread the test started and never released", state.name), byLock);
                report.lockLeaks.add(leak);
                return;
            }
        }
    }

    /** One {@link Violation} per finding the gate reads: leaks, held locks, then long holds. */
    private static void addStructuredViolations(LockLeakReport report) {
        if (!report.hasIssues()) {
            return;
        }
        // The severity the failOn gate read from this text before #801: a marker in it,
        // else the value DetectorDefaultSeverity declared for the detector.
        IssueSeverity severity = IssueSeverity.markedIn(report.toString()).orElse(IssueSeverity.CRITICAL);
        for (List<String> findings : List.of(report.lockLeaks, report.heldLocks, report.excessiveHoldTimes)) {
            for (String finding : findings) {
                report.structuredViolations.add(new Violation("LockLeaks", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
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
     * unnamed virtual thread's, a name another live platform thread or another live thread that
     * registered or recorded against the lock also carries is ambiguous (#855), and a name the
     * recorded thread does not carry belongs to a thread that never recorded: finding no live
     * platform thread of that name says nothing about a virtual one, which no scan can list. Each
     * stays at its recorded grade. {@code ReentrantLockDetector} judges its own held-lock finding by
     * the same rule, through the same code (#848). What remains is a virtual thread that never
     * recorded against the lock and carries the recorded thread's non-empty name.
     */
    private static @Nullable String confirmedHold(ReentrantLock lock, @Nullable Thread recorded,
                                                  Set<Thread> platformThreads, Set<Thread> recordedThreads) {
        if (!lock.isLocked() || lock.isHeldByCurrentThread()) {
            return null;
        }
        String holderName = ReentrantLockDetector.holderNameOf(lock);
        if (holderName == null) {
            return null;
        }
        // The owner itself where it can be read (#855), the name otherwise; the same answer
        // ReentrantLockDetector gives for this hold.
        ReentrantLockDetector.HolderState byOwner = ReentrantLockDetector.ownerState(lock);
        ReentrantLockDetector.HolderState state = byOwner != null ? byOwner
                : ReentrantLockDetector.holderState(holderName, recorded, platformThreads, recordedThreads);
        if (state == null || state == ReentrantLockDetector.HolderState.WORKING) {
            return null;
        }
        return String.format(" - ReentrantLock.isLocked() confirms it: held by '%s', %s",
                holderName, state == ReentrantLockDetector.HolderState.IDLE
                        ? "now idle in its pool" : "which has finished");
    }

    /**
     * Report class for lock leak analysis.
     */
    public static class LockLeakReport implements GradedFindings {
        private boolean enabled = true;
        final List<String> lockLeaks = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        final List<String> heldLocks = new ArrayList<>();
        final List<String> excessiveHoldTimes = new ArrayList<>();
        /**
         * One line per lock object, named but not keyed by the name: two locks may
         * share a name, and filed under it the second one's line overwrote the first's (#789).
         */
        final List<String> threadActivity = new ArrayList<>();
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
        public List<GradedFindings.Grade> grades() {
            if (!hasIssues()) {
                return List.of();
            }
            IssueSeverity severity = DetectorDefaultSeverity.of(LockLeakDetector.class.getSimpleName(), toString());
            List<GradedFindings.Grade> out = new ArrayList<>();
            for (String leak : lockLeaks) {
                out.add(gradeOf(severity, leak));
            }
            for (String held : heldLocks) {
                out.add(gradeOf(severity, held));
            }
            for (String slow : excessiveHoldTimes) {
                out.add(new GradedFindings.Grade(severity, TrustTier.PROMPT, slow, DetectorTrust.Evidence.HEURISTIC));
            }
            return List.copyOf(out);
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
