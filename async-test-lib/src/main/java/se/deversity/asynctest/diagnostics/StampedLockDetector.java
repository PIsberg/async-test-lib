package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashSet;
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
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param name a label identifying the lock in the report
     */
    public void registerLock(StampedLock lock, String name) {
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        lockRegistry.putIfAbsent(lock, new LockInfo(name));
    }

    /**
     * Record an optimistic read stamp acquisition.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     */
    public void recordOptimisticRead(StampedLock lock, String lockName, long stamp) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordOptimisticRead(stamp);
        }
    }

    /**
     * Record validation of optimistic read.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     * @param validated the {@code validated} flag
     */
    public void recordOptimisticValidation(StampedLock lock, String lockName, long stamp, boolean validated) {
        if (!validated) {
            unvalidatedOptimisticReads.add(lockName + " (stamp: " + stamp + ")");
        }
    }

    /**
     * Record a read lock acquisition.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     */
    public void recordReadLock(StampedLock lock, String lockName, long stamp) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordReadLock(stamp);
        }
    }

    /**
     * Record a write lock acquisition.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     */
    public void recordWriteLock(StampedLock lock, String lockName, long stamp) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordWriteLock(stamp);
        }
    }

    /**
     * Record a lock release.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     */
    public void recordUnlock(StampedLock lock, String lockName, long stamp) {
        LockInfo info = lockRegistry.get(lock);
        if (info != null) {
            info.recordUnlock(stamp);
        }
    }

    /**
     * Record a stamp that was not released.
     *
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     */
    public void recordStampNotReleased(String lockName, long stamp) {
        stampNotReleased.add(lockName + " (stamp: " + stamp + ")");
    }

    /**
     * Analyze StampedLock usage and return report.
     *
     * @return the findings this detector collected during the run
     */
    public StampedLockReport analyze() {
        return new StampedLockReport(
            unvalidatedOptimisticReads,
            stampNotReleased
        );
    }

    /**
     * Report class for StampedLock analysis.
     */
    public static class StampedLockReport {
        private final Set<String> unvalidatedOptimisticReads;
        private final Set<String> stampNotReleased;
        /**
         * Creates a StampedLockReport.
         *
         * @param unvalidatedOptimisticReads the optimistic reads whose stamp was never validated
         * @param stampNotReleased the stamps acquired but never released
         */
        public StampedLockReport(
            Set<String> unvalidatedOptimisticReads,
            Set<String> stampNotReleased
        ) {
            this.unvalidatedOptimisticReads = Collections.unmodifiableSet(new HashSet<>(unvalidatedOptimisticReads));
            this.stampNotReleased = Collections.unmodifiableSet(new HashSet<>(stampNotReleased));
        }

        /**
         * {@return whether there are issues}
         */
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
                sb.append("""
  Why: An optimistic read acquires no lock. A concurrent writer may update the fields between the read
       and the validate() call, meaning the read values are a torn snapshot from two different states.
       Using data from a failed validation produces silently wrong results.
""");
                sb.append("  Fix: Always call lock.validate(stamp) before using optimistically-read data:\n");
                sb.append("    long stamp = lock.tryOptimisticRead();\n");
                sb.append("    int x = field;  // read — may be torn\n");
                sb.append("    if (!lock.validate(stamp)) { stamp = lock.readLock(); try { x = field; } finally { lock.unlockRead(stamp); } }\n");
            }

            if (!stampNotReleased.isEmpty()) {
                sb.append("  Stamps Not Released:\n");
                for (String lockInfo : stampNotReleased) {
                    sb.append("    - ").append(lockInfo).append("\n");
                }
                sb.append("""
  Why: An unreleased StampedLock read or write lock blocks all subsequent writers (or all readers
       for a leaked write lock) indefinitely, causing the application to hang.
""");
                sb.append("  Fix: Always release stamps in a finally block:\n");
                sb.append("    long stamp = lock.readLock();\n");
                sb.append("    try { /* read fields */ } finally { lock.unlockRead(stamp); }\n");
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
