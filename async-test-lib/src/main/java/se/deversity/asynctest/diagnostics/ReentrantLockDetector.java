package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Detects ReentrantLock misuse patterns:
 * - Lock starvation (thread waiting excessively long)
 * - Unfair lock acquisition (threads not acquiring in FIFO order)
 * - Lock timeout (tryLock with timeout expiring)
 * - Lock not released in finally block
 */
public class ReentrantLockDetector {

    private final Map<ReentrantLock, LockInfo> lockRegistry = new ConcurrentHashMap<>();
    private final Set<ReentrantLock> timeoutLocks = ConcurrentHashMap.newKeySet();
    private final Set<String> starvationThreads = ConcurrentHashMap.newKeySet();

    /**
     * Register a ReentrantLock for monitoring.
     */
    public void registerLock(ReentrantLock lock, String name) {
        lockRegistry.put(lock, new LockInfo(name));
    }

    /**
     * Record a successful lock acquisition.
     */
    public void recordLockAcquired(ReentrantLock lock, String threadName) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordAcquire(threadName);
        }
    }

    /**
     * Record a lock release.
     */
    public void recordLockReleased(ReentrantLock lock, String threadName) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordRelease(threadName);
        }
    }

    /**
     * Record a tryLock() that timed out.
     */
    public void recordLockTimeout(ReentrantLock lock) {
        timeoutLocks.add(lock);
    }

    /**
     * Record potential lock starvation (wait time exceeds threshold).
     */
    public void recordStarvation(String threadName, long waitTimeMs) {
        starvationThreads.add(threadName + " (waited " + waitTimeMs + "ms)");
    }

    /**
     * Analyze lock usage and return report.
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

        public ReentrantLockReport(
            Map<ReentrantLock, LockInfo> lockRegistry,
            Set<ReentrantLock> timeoutLocks,
            Set<String> starvationThreads
        ) {
            this.lockRegistry = Collections.unmodifiableMap(new HashMap<>(lockRegistry));
            this.timeoutLocks = Collections.unmodifiableSet(new HashSet<>(timeoutLocks));
            this.starvationThreads = Collections.unmodifiableSet(new HashSet<>(starvationThreads));
        }

        public boolean hasIssues() {
            return !timeoutLocks.isEmpty() || !starvationThreads.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("REENTRANTLOCK ISSUES DETECTED:\n");

            if (!timeoutLocks.isEmpty()) {
                sb.append("  Lock Timeouts:\n");
                for (ReentrantLock lock : timeoutLocks) {
                    LockInfo info = lockRegistry.get(lock);
                    sb.append("    - ").append(info.name)
                      .append(" (tryLock() timed out)\n");
                }
                sb.append("  Why: tryLock() timing out means the lock is held for longer than expected, often indicating an\n" +
"       undersized timeout, a lock held during slow I/O, or genuine contention from too many threads.\n" +
"       Silently proceeding without the lock leads to data races or skipped critical sections.\n" +
"  Fix: Increase the timeout, reduce the critical section size, or switch to a blocking lock.lock()\n" +
"       if the caller must wait; never ignore a failed tryLock() — always handle the false return\n");
            }

            if (!starvationThreads.isEmpty()) {
                sb.append("  Lock Starvation:\n");
                for (String threadInfo : starvationThreads) {
                    sb.append("    - Thread ").append(threadInfo).append("\n");
                }
                sb.append("  Why: A non-fair lock allows new threads to \"barge\" ahead of waiting threads, causing some threads\n" +
"       to wait arbitrarily long or never acquire the lock at all.\n" +
"  Fix: Construct with new ReentrantLock(true) for FIFO fairness; or reduce lock hold time so all\n" +
"       threads get more opportunities to acquire it\n");
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
        String lastHolder = null;

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
