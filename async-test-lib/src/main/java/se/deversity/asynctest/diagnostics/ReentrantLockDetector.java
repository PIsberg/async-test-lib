package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Detects ReentrantLock misuse patterns.
 *
 * <p><strong>What it reports:</strong> a {@code tryLock} that timed out
 * ({@link #recordLockTimeout}) and a thread that waited past the starvation threshold
 * ({@link #recordStarvation}). Those are the two things {@link ReentrantLockReport#hasIssues()}
 * gates on.
 *
 * <p><strong>What it records but does not report:</strong> acquire and release counts. They are
 * context in the report, not a finding, because an unbalanced pair is as likely to mean the two
 * halves were instrumented in different places as it is to mean a hold was leaked.
 * {@link LockLeakDetector} is the detector for that question, and since issue #368 this one
 * forwards its registrations and records there whenever both are enabled, so a caller who
 * instruments only these methods still gets the leak reported rather than silence. See
 * {@link #deferLeakReportingTo}.
 */
public class ReentrantLockDetector {

    private final Map<ReentrantLock, LockInfo> lockRegistry = new ConcurrentHashMap<>();
    private final Set<ReentrantLock> timeoutLocks = ConcurrentHashMap.newKeySet();
    private final Set<String> starvationThreads = ConcurrentHashMap.newKeySet();

    /**
     * Where to send acquire and release records, or {@code null} to keep them as context only.
     *
     * <p>{@link ReentrantLockReport#hasIssues()} gates on timeouts and starvation. The acquire and
     * release counts are recorded and printed but never trip it, which is right: this detector has
     * no way to tell an unbalanced pair caused by a leak from one caused by instrumentation that
     * records the two halves in different places. {@link LockLeakDetector} is the detector for
     * that question - it reports both the imbalance and a lock still held when analysis runs.
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
     * Record a tryLock() that timed out.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     */
    public void recordLockTimeout(ReentrantLock lock) {
        if (lock == null) return;
        timeoutLocks.add(lock);
    }

    /**
     * Record potential lock starvation (wait time exceeds threshold).
     *
     * @param threadName a label identifying the thread in the report
     * @param waitTimeMs the wait time in milliseconds
     */
    public void recordStarvation(String threadName, long waitTimeMs) {
        starvationThreads.add(threadName + " (waited " + waitTimeMs + "ms)");
    }

    /**
     * Analyze lock usage and return report.
     *
     * @return the findings this detector collected during the run
     */
    public ReentrantLockReport analyze() {
        return new ReentrantLockReport(
            lockRegistry,
            timeoutLocks,
            starvationThreads
        );
    }

    /**
     * Report class for ReentrantLock analysis.
     */
    public static class ReentrantLockReport {
        private final Map<ReentrantLock, LockInfo> lockRegistry;
        private final Set<ReentrantLock> timeoutLocks;
        private final Set<String> starvationThreads;
        /**
         * Creates a ReentrantLockReport.
         *
         * @param lockRegistry every registered lock and what was observed on it
         * @param timeoutLocks the locks whose timed acquisition failed
         * @param starvationThreads the threads that waited long enough to count as starved
         */
        public ReentrantLockReport(
            Map<ReentrantLock, LockInfo> lockRegistry,
            Set<ReentrantLock> timeoutLocks,
            Set<String> starvationThreads
        ) {
            this.lockRegistry = Collections.unmodifiableMap(new HashMap<>(lockRegistry));
            this.timeoutLocks = Collections.unmodifiableSet(new HashSet<>(timeoutLocks));
            this.starvationThreads = Collections.unmodifiableSet(new HashSet<>(starvationThreads));
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !timeoutLocks.isEmpty() || !starvationThreads.isEmpty();
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

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("REENTRANTLOCK ISSUES DETECTED:\n");

            if (!timeoutLocks.isEmpty()) {
                sb.append("  Lock Timeouts:\n");
                for (ReentrantLock lock : timeoutLocks) {
                    LockInfo info = infoFor(lock);
                    sb.append("    - ").append(info.name)
                      .append(" (tryLock() timed out)\n");
                }
                sb.append("""
  Why: tryLock() timing out means the lock is held for longer than expected, often indicating an
       undersized timeout, a lock held during slow I/O, or genuine contention from too many threads.
       Silently proceeding without the lock leads to data races or skipped critical sections.
  Fix: Increase the timeout, reduce the critical section size, or switch to a blocking lock.lock()
       if the caller must wait; never ignore a failed tryLock() — always handle the false return
""");
            }

            if (!starvationThreads.isEmpty()) {
                sb.append("  Lock Starvation:\n");
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
    }
}
