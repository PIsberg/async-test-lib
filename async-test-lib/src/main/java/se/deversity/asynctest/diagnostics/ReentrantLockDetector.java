package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Detects ReentrantLock misuse patterns.
 *
 * <p><strong>What it reports:</strong> a lock that is still held when the run is analysed, by a
 * thread other than the one analysing ({@link #analyze}), and a starvation the caller recorded
 * ({@link #recordStarvation}). Those are the two things {@link ReentrantLockReport#hasIssues()}
 * gates on.
 *
 * <p>The held lock is asked of the lock itself: {@link ReentrantLock#isLocked()} on every lock this
 * detector was told about, once the bodies have finished. A hold nobody gave back is what a
 * {@code lock()} without its {@code unlock()} leaves behind, whether the missing release is an
 * exception path with no {@code finally} or a helper that re-enters the lock and never releases
 * the extra hold; either way every later caller parks in {@code lock()} for good. Recorded acquire
 * and release counts cannot see the second shape, because the pair the caller instrumented is
 * balanced; the lock can. A thread still running when analysis starts and legitimately holding the
 * lock would also be reported, which is why the runner analyses only after its workers have
 * finished. The analysing thread's own holds are never reported.
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
    private final Set<String> starvationThreads = ConcurrentHashMap.newKeySet();

    /**
     * Where to send acquire and release records, or {@code null} to keep them as context only.
     *
     * <p>{@link ReentrantLockReport#hasIssues()} gates on a lock held at analysis and on starvation.
     * The acquire and release counts are recorded and printed but never trip it, which is right:
     * this detector has no way to tell an unbalanced pair caused by a leak from one caused by
     * instrumentation that records the two halves in different places. {@link LockLeakDetector} is
     * the detector for that question.
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
    }

    /**
     * Record a successful lock acquisition.
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
    }

    /**
     * Record a lock starvation the caller observed.
     *
     * <p>The caller decides what counts as starvation; this detector applies no threshold of its
     * own, because a wall-clock duration is not evidence on a loaded machine (a GC pause or a busy
     * CI runner stretches every wait). Any positive wait recorded here is reported. A wait of zero
     * or less is not a wait, so it is ignored.
     *
     * @param threadName a label identifying the thread in the report
     * @param waitTimeMs how long the thread waited, in milliseconds; ignored unless positive
     */
    public void recordStarvation(String threadName, long waitTimeMs) {
        if (waitTimeMs <= 0) return;
        starvationThreads.add(threadName + " (waited " + waitTimeMs + "ms)");
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
        for (ReentrantLock lock : known) {
            if (lock.isLocked() && !lock.isHeldByCurrentThread() && !leftToLeakReporter(lock)) {
                held.put(lock, ReentrantLockReport.holderOf(lock));
            }
        }
        return new ReentrantLockReport(lockRegistry, timeouts, starvationThreads, held);
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
        private final Set<String> starvationThreads;
        /** Each lock held at analysis, with its holder as the lock described it then. */
        private final Map<ReentrantLock, String> heldLocks;

        /**
         * Creates a ReentrantLockReport with no held locks.
         *
         * <p>Kept for source compatibility. Since #589 a timeout is context rather than a finding,
         * so a report built this way has issues only when {@code starvationThreads} is non-empty.
         *
         * @param lockRegistry every registered lock and what was observed on it
         * @param timeoutLocks the locks whose timed acquisition failed, printed as context
         * @param starvationThreads the threads recorded as starved
         */
        public ReentrantLockReport(
            Map<ReentrantLock, LockInfo> lockRegistry,
            Set<ReentrantLock> timeoutLocks,
            Set<String> starvationThreads
        ) {
            this(lockRegistry, countOnce(timeoutLocks), starvationThreads, Map.of());
        }

        private ReentrantLockReport(
            Map<ReentrantLock, LockInfo> lockRegistry,
            Map<ReentrantLock, Integer> timeouts,
            Set<String> starvationThreads,
            Map<ReentrantLock, String> heldLocks
        ) {
            this.lockRegistry = Collections.unmodifiableMap(new HashMap<>(lockRegistry));
            this.timeouts = Collections.unmodifiableMap(new HashMap<>(timeouts));
            this.starvationThreads = Collections.unmodifiableSet(new HashSet<>(starvationThreads));
            this.heldLocks = Collections.unmodifiableMap(new LinkedHashMap<>(heldLocks));
        }

        private static Map<ReentrantLock, Integer> countOnce(Set<ReentrantLock> locks) {
            Map<ReentrantLock, Integer> counted = new HashMap<>();
            for (ReentrantLock lock : locks) {
                counted.put(lock, 1);
            }
            return counted;
        }

        /**
         * {@return whether a lock was held at analysis or a starvation was recorded}
         */
        public boolean hasIssues() {
            return !heldLocks.isEmpty() || !starvationThreads.isEmpty();
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
            int open = described.lastIndexOf('[');
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

            if (!starvationThreads.isEmpty()) {
                sb.append("  Lock Starvation (as recorded by the caller):\n");
                for (String threadInfo : starvationThreads) {
                    sb.append("    - Thread ").append(threadInfo).append("\n");
                }
                sb.append("""
  Why: A non-fair lock allows new threads to "barge" ahead of waiting threads, causing some threads
       to wait arbitrarily long or never acquire the lock at all.
  Fix: Construct with new ReentrantLock(true) for FIFO fairness; or reduce lock hold time so all
       threads get more opportunities to acquire it
""");
            }

            if (!hasIssues()) {
                sb.append("  No ReentrantLock issues detected.\n");
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
