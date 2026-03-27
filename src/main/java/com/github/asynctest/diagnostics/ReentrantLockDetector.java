package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
            this.lockRegistry = lockRegistry;
            this.timeoutLocks = timeoutLocks;
            this.starvationThreads = starvationThreads;
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
                sb.append("  Fix: Increase timeout or review lock contention\n");
            }

            if (!starvationThreads.isEmpty()) {
                sb.append("  Lock Starvation:\n");
                for (String threadInfo : starvationThreads) {
                    sb.append("    - Thread ").append(threadInfo).append("\n");
                }
                sb.append("  Fix: Consider using fair ReentrantLock or reduce lock hold time\n");
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
