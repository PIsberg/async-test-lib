package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Detects ReentrantLock misuse patterns.
 *
 * <p><strong>What it reports:</strong> a lock still held when the run is analysed by a thread that
 * is no longer working ({@link #analyze}), and a starvation the lock itself corroborated
 * ({@link #recordStarvation(ReentrantLock, String, long)}). Those are the two things
 * {@link ReentrantLockReport#hasIssues()} gates on.
 *
 * <p>The held lock is asked of the lock itself: {@link ReentrantLock#isLocked()} on every lock this
 * detector was told about, once the bodies have finished. A hold nobody gave back is what a
 * {@code lock()} without its {@code unlock()} leaves behind, whether the missing release is an
 * exception path with no {@code finally} or a helper that re-enters the lock and never releases
 * the extra hold; either way every later caller parks in {@code lock()} for good. Recorded acquire
 * and release counts cannot see the second shape, because the pair the caller instrumented is
 * balanced; the lock can. The analysing thread's own holds are never reported.
 *
 * <p>A hold is a leak only if its holder has stopped working (#609). The holder is named by the
 * lock ({@code "Locked by thread X"}) and looked up among the threads that recorded against the lock
 * and the live platform threads. The hold is reported when no thread of that name is alive (the
 * holder finished with the lock taken), or when every one that is sits idle in a pool
 * ({@code ThreadPoolExecutor.getTask}, {@code ForkJoinPool.awaitWork}), which is how a runner worker
 * or an executor thread looks once the task that took the lock has ended. A holder that is alive
 * and anywhere else may still release the lock, so its hold is printed as context and not judged.
 * The boundary: a virtual thread the body started, which never recorded against the lock and is
 * still working when analysis starts, cannot be found (virtual threads are not enumerable), so its
 * hold is reported as if the holder had finished.
 *
 * <p><strong>What it records but does not report:</strong>
 * <ul>
 *   <li>{@code tryLock} timeouts ({@link #recordLockTimeout}). A timeout is how a timed acquire
 *       says the lock was busy, and a caller that backs off on the {@code false} return has done
 *       the right thing. Only a caller that discards the return and runs the critical section
 *       unguarded is wrong, and that is invisible to a record call made after the fact;
 *       {@code TRY_LOCK_MISUSE} observes the use of the return value and is the detector for it
 *       (#589). Timeouts are printed as context, and a timed-out lock is also checked for a held
 *       lock at analysis, which is often what the timeout was a symptom of.</li>
 *   <li>A recorded wait the lock did not corroborate ({@link #recordStarvation(String, long)}, or
 *       the lock overload with no barging seen). How long a thread waited is not evidence of
 *       starvation: a GC pause or a busy CI runner stretches every wait (#575), and a long queue
 *       behind a slow critical section is contention, not unfairness.</li>
 *   <li>Acquire and release counts. An unbalanced pair is as likely to mean the two halves were
 *       instrumented in different places as it is to mean a hold was leaked.
 *       {@link LockLeakDetector} is the detector for that question, and since issue #368 this one
 *       forwards its registrations and records there whenever both are enabled, so a caller who
 *       instruments only these methods still gets the imbalance reported rather than silence. See
 *       {@link #deferLeakReportingTo}. When the forwarded counts already show a leak on a lock,
 *       the held lock is left to that detector, so one leak is one finding.</li>
 * </ul>
 */
public class ReentrantLockDetector {

    private final Map<ReentrantLock, LockInfo> lockRegistry = new ConcurrentHashMap<>();
    /** Timeouts per lock. {@code ReentrantLock} keeps {@code Object}'s equality, so keys are identities. */
    private final Map<ReentrantLock, Integer> timeouts = new ConcurrentHashMap<>();
    /** What was seen on each lock at record time: who touched it, and who was passed over. */
    private final Map<ReentrantLock, Observed> observed = new ConcurrentHashMap<>();
    /** Starvations the lock corroborated: the finding. */
    private final Set<String> observedStarvation = ConcurrentHashMap.newKeySet();
    /** Recorded waits nothing corroborated: context. */
    private final Set<String> recordedWaits = ConcurrentHashMap.newKeySet();

    /**
     * Where to send acquire and release records, or {@code null} to keep them as context only.
     *
     * <p>{@link ReentrantLockReport#hasIssues()} gates on a lock held at analysis and on
     * corroborated starvation. The acquire and release counts are recorded and printed but never
     * trip it, which is right: this detector has no way to tell an unbalanced pair caused by a leak
     * from one caused by instrumentation that records the two halves in different places.
     * {@link LockLeakDetector} is the detector for that question.
     *
     * <p>What was wrong was the silence. The method names here invite a caller to record acquire
     * and release and expect a leak to be reported, and nothing said otherwise; a leaked hold went
     * unreported unless they had also instrumented a second detector's separate API. So the
     * registry hands this one the peer when both are enabled and every registration and record is
     * forwarded, which means the finding comes out under the name that owns it whichever API was
     * instrumented. See issue #368, and #361 for the same arrangement between the two read-write
     * lock detectors.
     */
    private volatile @Nullable LockLeakDetector leakReporter;

    /**
     * Sends acquire and release records to {@code peer}, which is the detector that reports leaks.
     *
     * <p>Called by {@code DetectorRegistry} when both detectors are enabled, which {@code
     * detectAll} makes the default. With no peer set this detector's own behaviour is unchanged.
     *
     * @param peer the detector that will report lock leaks, or {@code null} to forward nothing
     */
    public void deferLeakReportingTo(@Nullable LockLeakDetector peer) {
        this.leakReporter = peer;
        if (peer != null) {
            lockRegistry.forEach((lock, info) -> peer.registerLock(lock, info.name));
        }
    }

    /** {@return the registered name of {@code lock}, or a stable fallback} */
    private String nameOf(ReentrantLock lock) {
        LockInfo info = lockRegistry.get(lock);
        return info != null ? info.name : "ReentrantLock@" + System.identityHashCode(lock);
    }

    /** {@return what has been seen on {@code lock}, created on first use} */
    private Observed observedFor(ReentrantLock lock) {
        Observed seen = observed.get(lock);
        if (seen == null) {
            Observed fresh = new Observed();
            seen = observed.putIfAbsent(lock, fresh);
            if (seen == null) {
                seen = fresh;
            }
        }
        return seen;
    }

    /**
     * Register a ReentrantLock for monitoring.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param name a label identifying the lock in the report
     */
    public void registerLock(ReentrantLock lock, String name) {
        if (lock == null) return;
        LockLeakDetector peer = leakReporter;
        if (peer != null) {
            peer.registerLock(lock, name);
        }
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        lockRegistry.putIfAbsent(lock, new LockInfo(name));
        observedFor(lock).touch();
    }

    /**
     * Record a successful lock acquisition.
     *
     * <p>Call it while holding the lock, straight after acquiring. The lock is asked then which of
     * the threads that recorded against it are still queued, which is how a thread passed over by
     * another that barged ahead of it twice is seen (see
     * {@link #recordStarvation(ReentrantLock, String, long)}).
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param threadName a label identifying the thread in the report
     */
    public void recordLockAcquired(ReentrantLock lock, String threadName) {
        if (lock == null) return;
        LockLeakDetector peer = leakReporter;
        if (peer != null) {
            peer.recordLockAcquired(lock, nameOf(lock));
        }
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordAcquire(threadName);
        }
        Observed seen = observedFor(lock);
        seen.touch();
        seen.observeAcquisition(lock, Thread.currentThread());
    }

    /**
     * Record a lock release.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param threadName a label identifying the thread in the report
     */
    public void recordLockReleased(ReentrantLock lock, String threadName) {
        if (lock == null) return;
        LockLeakDetector peer = leakReporter;
        if (peer != null) {
            peer.recordLockReleased(lock, nameOf(lock));
        }
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordRelease(threadName);
        }
        observedFor(lock).touch();
    }

    /**
     * Record a {@code tryLock} that timed out.
     *
     * <p>Context, not a finding: a timeout the caller handles is correct code, and whether the
     * {@code false} return was discarded cannot be seen from here ({@code TRY_LOCK_MISUSE} can see
     * it). The lock is remembered, printed with its timeout count, and checked at analysis for a
     * hold nobody gave back, which is the finding a timeout is most often the symptom of.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     */
    public void recordLockTimeout(ReentrantLock lock) {
        if (lock == null) return;
        timeouts.merge(lock, 1, Integer::sum);
        observedFor(lock).touch();
    }

    /**
     * Record a wait the caller took for starvation, with no lock to check it against.
     *
     * <p>Context, not a finding (#608). Nothing here can ask a lock whether any thread was passed
     * over, and a wait's length alone is not evidence: a GC pause or a busy CI runner stretches
     * every wait (#575). Printed so the caller's observation is not lost; use
     * {@link #recordStarvation(ReentrantLock, String, long)} to have it judged. A wait of zero or
     * less is not a wait, so it is ignored.
     *
     * @param threadName a label identifying the thread in the report
     * @param waitTimeMs how long the thread waited, in milliseconds; ignored unless positive
     */
    public void recordStarvation(String threadName, long waitTimeMs) {
        if (waitTimeMs <= 0) return;
        recordedWaits.add(threadName + " (waited " + waitTimeMs + "ms; no lock named, so nothing "
                + "could corroborate it)");
    }

    /**
     * Record a wait on {@code lock} that the caller took for starvation, to be judged against what
     * the lock showed.
     *
     * <p>Call it from the thread that waited, once it has the lock. It is a finding when this
     * detector saw that thread passed over by a barger: another thread recorded acquiring the lock
     * twice while this one stayed queued ({@link ReentrantLock#hasQueuedThread(Thread)} at each of
     * that thread's {@link #recordLockAcquired} calls). That is the mechanism of starvation on a
     * {@code ReentrantLock}: a non-fair {@code lock()}, or an untimed {@code tryLock()} on any lock,
     * takes a free lock ahead of the queue. A fair lock taken through {@code lock()} hands itself to
     * the longest waiter, so the same load there is never a finding. A wait with no barging seen is
     * printed as context: its length alone is not evidence (#575). No duration threshold is
     * applied. The waiting thread must have recorded against the lock (for example
     * {@link #registerLock}) before it queued, so that the barger's records can ask about it.
     *
     * @param lock the lock the thread waited for, tracked by identity rather than equality
     * @param threadName a label identifying the thread in the report
     * @param waitTimeMs how long the thread waited, in milliseconds; ignored unless positive
     * @since 1.12.1
     */
    public void recordStarvation(ReentrantLock lock, String threadName, long waitTimeMs) {
        if (lock == null || waitTimeMs <= 0) return;
        Observed seen = observedFor(lock);
        seen.touch();
        int barged = seen.timesBargedPast(Thread.currentThread());
        String lockName = nameOf(lock);
        if (barged > 0) {
            observedStarvation.add(threadName + " on " + lockName + ": waited " + waitTimeMs
                    + "ms, and another thread took the lock again " + barged
                    + " time(s) while it stayed queued");
        } else {
            recordedWaits.add(threadName + " on " + lockName + " (waited " + waitTimeMs
                    + "ms; nobody was seen barging past it"
                    + (lock.isFair() ? ", and a fair lock taken by lock() cannot be barged)" : ")"));
        }
    }

    /**
     * Analyze lock usage and return report.
     *
     * <p>Reads each known lock's state now, so call it once the threads that used the locks have
     * finished; the runner does.
     *
     * @return the findings this detector collected during the run
     */
    public ReentrantLockReport analyze() {
        Set<ReentrantLock> known = Collections.newSetFromMap(new IdentityHashMap<>());
        known.addAll(lockRegistry.keySet());
        known.addAll(timeouts.keySet());
        // The holder is read now, while it is the evidence, not when the report is printed.
        Map<ReentrantLock, String> held = new LinkedHashMap<>();
        Map<ReentrantLock, String> stillWorking = new LinkedHashMap<>();
        Set<Thread> platformThreads = null;
        for (ReentrantLock lock : known) {
            if (!lock.isLocked() || lock.isHeldByCurrentThread() || leftToLeakReporter(lock)) {
                continue;
            }
            String holder = ReentrantLockReport.holderOf(lock);
            String holderName = holderNameOf(lock);
            if (holderName == null) {
                stillWorking.put(lock, holder + ", holder could not be identified");
                continue;
            }
            if (platformThreads == null) {
                platformThreads = Thread.getAllStackTraces().keySet();
            }
            HolderState state = stateOf(holderName, lock, platformThreads);
            if (state == HolderState.WORKING) {
                stillWorking.put(lock, holder + ", still running");
            } else {
                held.put(lock, holder + (state == HolderState.IDLE
                        ? ", now idle in its pool" : ", which has finished"));
            }
        }
        return new ReentrantLockReport(lockRegistry, timeouts, observedStarvation, recordedWaits,
                held, stillWorking);
    }

    /** Where the thread holding a lock is when the run is analysed. */
    private enum HolderState { GONE, IDLE, WORKING }

    private HolderState stateOf(String holderName, ReentrantLock lock, Set<Thread> platformThreads) {
        Set<Thread> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        Observed seen = observed.get(lock);
        if (seen != null) {
            candidates.addAll(seen.threads);
        }
        candidates.addAll(platformThreads);
        boolean anyAlive = false;
        for (Thread thread : candidates) {
            if (!thread.isAlive() || !holderName.equals(thread.getName())) {
                continue;
            }
            anyAlive = true;
            if (!idleInAPool(thread)) {
                return HolderState.WORKING;
            }
        }
        return anyAlive ? HolderState.IDLE : HolderState.GONE;
    }

    /**
     * {@return whether {@code thread} is waiting for its pool's next task, so the task that ran on it
     * has ended}
     */
    static boolean idleInAPool(Thread thread) {
        for (StackTraceElement frame : thread.getStackTrace()) {
            String cls = frame.getClassName();
            String method = frame.getMethodName();
            if (("java.util.concurrent.ThreadPoolExecutor".equals(cls) && "getTask".equals(method))
                    || ("java.util.concurrent.ForkJoinPool".equals(cls) && "awaitWork".equals(method))) {
                return true;
            }
        }
        return false;
    }

    /** {@return the holder's thread name as the lock describes it, or {@code null} if it does not} */
    static @Nullable String holderNameOf(ReentrantLock lock) {
        String described = lock.toString();
        String marker = "[Locked by thread ";
        int at = described.lastIndexOf(marker);
        return at >= 0 && described.endsWith("]")
                ? described.substring(at + marker.length(), described.length() - 1)
                : null;
    }

    /**
     * {@return whether {@link LockLeakDetector} already reports a leak on {@code lock} from the
     * forwarded counts, in which case the held lock is the same finding and is left to it}
     */
    private boolean leftToLeakReporter(ReentrantLock lock) {
        if (leakReporter == null) return false;
        LockInfo info = lockRegistry.get(lock);
        return info != null && info.isUnbalanced();
    }

    /**
     * Report class for ReentrantLock analysis.
     */
    public static class ReentrantLockReport {
        private final Map<ReentrantLock, LockInfo> lockRegistry;
        private final Map<ReentrantLock, Integer> timeouts;
        /** Starvations the lock corroborated. */
        private final Set<String> observedStarvation;
        /** Recorded waits nothing corroborated, printed as context. */
        private final Set<String> recordedWaits;
        /** Each lock held at analysis by a holder that stopped working, with that holder. */
        private final Map<ReentrantLock, String> heldLocks;
        /** Each lock held at analysis by a holder still working, printed as context. */
        private final Map<ReentrantLock, String> stillWorking;

        /**
         * Creates a ReentrantLockReport with no held locks.
         *
         * <p>Kept for source compatibility. Since #589 a timeout is context rather than a finding,
         * and since #608 a recorded wait is a finding only when the lock corroborated it, which
         * this constructor cannot know; the threads passed here are printed as recorded waits, and
         * a report built this way has no issues.
         *
         * @param lockRegistry every registered lock and what was observed on it
         * @param timeoutLocks the locks whose timed acquisition failed, printed as context
         * @param starvationThreads the threads recorded as waiting, printed as context
         */
        public ReentrantLockReport(
            Map<ReentrantLock, LockInfo> lockRegistry,
            Set<ReentrantLock> timeoutLocks,
            Set<String> starvationThreads
        ) {
            this(lockRegistry, countOnce(timeoutLocks), Set.of(), starvationThreads, Map.of(), Map.of());
        }

        private ReentrantLockReport(
            Map<ReentrantLock, LockInfo> lockRegistry,
            Map<ReentrantLock, Integer> timeouts,
            Set<String> observedStarvation,
            Set<String> recordedWaits,
            Map<ReentrantLock, String> heldLocks,
            Map<ReentrantLock, String> stillWorking
        ) {
            this.lockRegistry = Collections.unmodifiableMap(new HashMap<>(lockRegistry));
            this.timeouts = Collections.unmodifiableMap(new HashMap<>(timeouts));
            this.observedStarvation = Collections.unmodifiableSet(new HashSet<>(observedStarvation));
            this.recordedWaits = Collections.unmodifiableSet(new HashSet<>(recordedWaits));
            this.heldLocks = Collections.unmodifiableMap(new LinkedHashMap<>(heldLocks));
            this.stillWorking = Collections.unmodifiableMap(new LinkedHashMap<>(stillWorking));
        }

        private static Map<ReentrantLock, Integer> countOnce(Set<ReentrantLock> locks) {
            Map<ReentrantLock, Integer> counted = new HashMap<>();
            for (ReentrantLock lock : locks) {
                counted.put(lock, 1);
            }
            return counted;
        }

        /**
         * {@return whether a lock was left held by a holder that stopped working, or a starvation
         * was corroborated by the lock}
         */
        public boolean hasIssues() {
            return !heldLocks.isEmpty() || !observedStarvation.isEmpty();
        }

        /**
         * Registry lookup that always yields a non-null {@code LockInfo}.
         *
         * <p>Nothing requires a {@code record*} call's subject to have been passed to the matching
         * {@code register*} first — no precondition, no runtime check — and the two are written at
         * different places in a test. When the registration is missed the lookup returns
         * {@code null} and dereferencing it threw out of {@code toString()}. That NPE never reached
         * the user: {@code DetectorRegistry.ifIssue} catches it so one detector cannot discard the
         * whole sweep, so the finding was simply dropped and the report the user needed never
         * appeared. A placeholder keeps the finding and says plainly which subject was not
         * registered.
         */
        private LockInfo infoFor(ReentrantLock lock) {
            LockInfo info = lockRegistry.get(lock);
            return info != null ? info : new LockInfo("<unregistered lock>");
        }

        /** {@return who holds {@code lock}, as the lock itself describes it, e.g. "Locked by thread x"} */
        static String holderOf(ReentrantLock lock) {
            String described = lock.toString();
            int open = described.lastIndexOf("[Locked by thread ");
            if (open < 0) {
                open = described.lastIndexOf('[');
            }
            return open >= 0 && described.endsWith("]")
                    ? described.substring(open + 1, described.length() - 1)
                    : "locked";
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("REENTRANTLOCK ISSUES DETECTED:\n");

            if (!heldLocks.isEmpty()) {
                sb.append("  Lock Still Held At Analysis:\n");
                heldLocks.forEach((lock, holder) -> sb.append("    - ").append(infoFor(lock).name)
                        .append(" (").append(holder).append(", at analysis)\n"));
                sb.append("""
  Why: A ReentrantLock is released only when every lock() has its unlock(). A hold nobody gave back,
       from an exception path with no finally or a helper that re-enters the lock and never releases
       the extra hold, parks every later caller of lock() forever and makes every tryLock() time out.
  Fix: Pair each lock() with exactly one unlock() in a finally block directly after it; a helper
       that runs under the caller's lock should not acquire the lock again
""");
            }

            if (!observedStarvation.isEmpty()) {
                sb.append("  Lock Starvation (barging seen on the lock):\n");
                for (String starved : sortedCopy(observedStarvation)) {
                    sb.append("    - Thread ").append(starved).append("\n");
                }
                sb.append("""
  Why: A non-fair lock() or an untimed tryLock() takes a free lock ahead of the threads already
       queued for it, so a thread that keeps losing that race can wait arbitrarily long or never
       acquire the lock at all.
  Fix: Construct with new ReentrantLock(true) and acquire with lock() or a timed tryLock() for FIFO
       hand-off; or shorten the critical section so the barging thread holds the lock less often
""");
            }

            if (!hasIssues()) {
                sb.append("  No ReentrantLock issues detected.\n");
            }

            if (!stillWorking.isEmpty()) {
                sb.append("  Context - locks held at analysis by a thread still working (not judged):\n");
                stillWorking.forEach((lock, holder) -> sb.append("    - ").append(infoFor(lock).name)
                        .append(" (").append(holder).append(")\n"));
                sb.append("""
       The holder is alive and not idle in a pool, so it may still release the lock. A hold is a
       leak once its holder has finished or gone back to its pool.
""");
            }

            if (!recordedWaits.isEmpty()) {
                sb.append("  Context - recorded waits the lock did not corroborate (not a finding on their own):\n");
                for (String wait : sortedCopy(recordedWaits)) {
                    sb.append("    - ").append(wait).append("\n");
                }
                sb.append("""
       A wait's length is not evidence of starvation: a GC pause or a loaded machine stretches every
       wait, and a queue behind a slow critical section is contention.
""");
            }

            if (!timeouts.isEmpty()) {
                sb.append("  Context - tryLock() timeouts (not a finding on their own):\n");
                Set<String> lines = new LinkedHashSet<>();
                timeouts.forEach((lock, count) -> lines.add("    - " + infoFor(lock).name
                        + ": " + count + " timed out\n"));
                lines.forEach(sb::append);
                sb.append("""
       A timed-out tryLock() that backs off is correct. One whose false return is discarded, so the
       critical section runs without the lock, is a defect TRY_LOCK_MISUSE reports.
""");
            }

            return sb.toString();
        }

        private static List<String> sortedCopy(Set<String> lines) {
            List<String> sorted = new ArrayList<>(lines);
            Collections.sort(sorted);
            return sorted;
        }
    }

    /**
     * What one lock showed at record time.
     *
     * <p>{@link #threads} is every thread that recorded against the lock, which is who
     * {@link ReentrantLock#hasQueuedThread(Thread)} can be asked about (the lock does not list its
     * queue publicly) and where a virtual holder is found at analysis.
     */
    private static final class Observed {
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        /** Per queued thread, who acquired ahead of it during its current wait. Guarded by this. */
        private final Map<Thread, Set<Thread>> passedOverBy = new IdentityHashMap<>();
        /** Per thread, how many times a thread that had already acquired ahead of it did so again. Guarded by this. */
        private final Map<Thread, Integer> bargedPast = new IdentityHashMap<>();

        void touch() {
            threads.add(Thread.currentThread());
        }

        /** Called by {@code acquirer} while it holds {@code lock}. */
        void observeAcquisition(ReentrantLock lock, Thread acquirer) {
            boolean anyQueued = lock.hasQueuedThreads();
            synchronized (this) {
                if (!anyQueued) {
                    // Every wait in progress has ended: nobody is queued any more.
                    passedOverBy.clear();
                    return;
                }
                for (Thread thread : threads) {
                    // Thread keeps Object's equality, so equals is identity here.
                    if (thread.equals(acquirer) || !lock.hasQueuedThread(thread)) {
                        passedOverBy.remove(thread); // not waiting now, so a later wait starts fresh
                        continue;
                    }
                    Set<Thread> ahead = passedOverBy.computeIfAbsent(thread,
                            waiting -> Collections.newSetFromMap(new IdentityHashMap<>()));
                    if (!ahead.add(acquirer)) {
                        bargedPast.merge(thread, 1, Integer::sum);
                    }
                }
            }
        }

        synchronized int timesBargedPast(Thread thread) {
            return bargedPast.getOrDefault(thread, 0);
        }
    }

    /**
     * Internal lock information.
     */
    static class LockInfo {
        final String name;
        int acquireCount = 0;
        int releaseCount = 0;
        @Nullable String lastHolder = null;

        LockInfo(String name) {
            this.name = name;
        }

        synchronized void recordAcquire(String threadName) {
            acquireCount++;
            lastHolder = threadName;
        }

        synchronized void recordRelease(String threadName) {
            releaseCount++;
            if (lastHolder != null && lastHolder.equals(threadName)) {
                lastHolder = null;
            }
        }

        synchronized boolean isUnbalanced() {
            return acquireCount > releaseCount;
        }
    }
}
