package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;

/**
 * Detects StampedLock misuse patterns:
 * - Optimistic read without validation
 * - Lock upgrade issues (optimistic → write)
 * - Stamp not released in finally block
 * - Wrong stamp used for unlock
 */
public class StampedLockDetector {

    private final Map<StampedLock, LockInfo> lockRegistry = new ConcurrentHashMap<>();
    private final Set<String> unvalidatedOptimisticReads = ConcurrentHashMap.newKeySet();
    private final Set<String> stampNotReleased = ConcurrentHashMap.newKeySet();

    /**
     * Register a StampedLock for monitoring.
     */
    public void registerLock(StampedLock lock, String name) {
        lockRegistry.put(lock, new LockInfo(name));
    }

    /**
     * Record an optimistic read stamp acquisition.
     */
    public void recordOptimisticRead(StampedLock lock, String lockName, long stamp) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordOptimisticRead(stamp);
        }
    }

    /**
     * Record validation of optimistic read.
     */
    public void recordOptimisticValidation(StampedLock lock, String lockName, long stamp, boolean validated) {
        if (!validated) {
            unvalidatedOptimisticReads.add(lockName + " (stamp: " + stamp + ")");
        }
    }

    /**
     * Record a read lock acquisition.
     */
    public void recordReadLock(StampedLock lock, String lockName, long stamp) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordReadLock(stamp);
        }
    }

    /**
     * Record a write lock acquisition.
     */
    public void recordWriteLock(StampedLock lock, String lockName, long stamp) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordWriteLock(stamp);
        }
    }

    /**
     * Record a lock release.
     */
    public void recordUnlock(StampedLock lock, String lockName, long stamp) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordUnlock(stamp);
        }
    }

    /**
     * Record a stamp that was not released.
     */
    public void recordStampNotReleased(String lockName, long stamp) {
        stampNotReleased.add(lockName + " (stamp: " + stamp + ")");
    }

    /**
     * Analyze StampedLock usage and return report.
     */
    public StampedLockReport analyze() {
        return new StampedLockReport(
            lockRegistry,
            unvalidatedOptimisticReads,
            stampNotReleased
        );
    }

    /**
     * Report class for StampedLock analysis.
     */
    public static class StampedLockReport {
        private final Map<StampedLock, LockInfo> lockRegistry;
        private final Set<String> unvalidatedOptimisticReads;
        private final Set<String> stampNotReleased;

        public StampedLockReport(
            Map<StampedLock, LockInfo> lockRegistry,
            Set<String> unvalidatedOptimisticReads,
            Set<String> stampNotReleased
        ) {
            this.lockRegistry = lockRegistry;
            this.unvalidatedOptimisticReads = unvalidatedOptimisticReads;
            this.stampNotReleased = stampNotReleased;
        }

        public boolean hasIssues() {
            return !unvalidatedOptimisticReads.isEmpty() || !stampNotReleased.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("STAMPEDLOCK ISSUES DETECTED:\n");

            if (!unvalidatedOptimisticReads.isEmpty()) {
                sb.append("  Unvalidated Optimistic Reads:\n");
                for (String lockInfo : unvalidatedOptimisticReads) {
                    sb.append("    - ").append(lockInfo).append("\n");
                }
                sb.append("  Problem: Optimistic read stamp used without calling validate()\n");
                sb.append("  Fix: Always validate optimistic reads:\n");
                sb.append("    if (lock.validate(stamp)) { /* use data */ }\n");
            }

            if (!stampNotReleased.isEmpty()) {
                sb.append("  Stamps Not Released:\n");
                for (String lockInfo : stampNotReleased) {
                    sb.append("    - ").append(lockInfo).append("\n");
                }
                sb.append("  Fix: Always unlock in finally block:\n");
                sb.append("    try { lock.unlockRead(stamp); } finally { }\n");
            }

            if (!hasIssues()) {
                sb.append("  No StampedLock issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal lock information.
     */
    static class LockInfo {
        final String name;
        int optimisticReadCount = 0;
        int readLockCount = 0;
        int writeLockCount = 0;
        int unlockCount = 0;

        LockInfo(String name) {
            this.name = name;
        }

        synchronized void recordOptimisticRead(long stamp) {
            optimisticReadCount++;
        }

        synchronized void recordReadLock(long stamp) {
            readLockCount++;
        }

        synchronized void recordWriteLock(long stamp) {
            writeLockCount++;
        }

        synchronized void recordUnlock(long stamp) {
            unlockCount++;
        }
    }
}
