package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects incorrect usage of {@link java.util.concurrent.locks.StampedLock} optimistic reads:
 * reading data after {@code tryOptimisticRead()} without calling {@code validate(stamp)},
 * or continuing to use data after a failed validation.
 *
 * <p>An optimistic read stamp is only valid if no write lock was acquired between
 * {@code tryOptimisticRead()} and {@code validate(stamp)}. Using data from an invalidated
 * optimistic read silently introduces torn-snapshot data corruption.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.optimisticReadValidationMonitor();
 * long stamp = lock.tryOptimisticRead();
 * mon.recordOptimisticReadStarted(lock, stamp, Thread.currentThread());
 *
 * int x = sharedX;
 * mon.recordDataAccessed(lock, stamp, Thread.currentThread(), "sharedX");
 *
 * if (!lock.validate(stamp)) {
 *     mon.recordValidateCalled(lock, stamp, false, Thread.currentThread());
 *     // must re-read under a full lock here
 * } else {
 *     mon.recordValidateCalled(lock, stamp, true, Thread.currentThread());
 * }
 * }</pre>
 */
public class OptimisticReadValidationDetector {

    private static class OptimisticRead {
        final long         stamp;
        final String       threadName;
        final List<String> accessedFields = new ArrayList<>();

        OptimisticRead(long stamp, String threadName) {
            this.stamp = stamp;
            this.threadName = threadName;
        }
    }

    // key = lockIdentityHash:threadId
    private final Map<String, OptimisticRead> pendingReads = new ConcurrentHashMap<>();
    private final List<String>                violations   = new CopyOnWriteArrayList<>();

    private static String key(Object lock, Thread thread) {
        return System.identityHashCode(lock) + ":" + thread.threadId();
    }

    /**
     * Call immediately after {@code StampedLock.tryOptimisticRead()}.
     *
     * @param lock the lock
     * @param stamp the stamp
     * @param thread the thread
     */
    public void recordOptimisticReadStarted(Object lock, long stamp, Thread thread) {
        if (lock == null || thread == null) return;
        OptimisticRead replaced =
            pendingReads.put(key(lock, thread), new OptimisticRead(stamp, thread.getName()));
        // A new optimistic read replaces the pending one for this (lock, thread).
        // If the replaced read had accessed data without ever being validated, that
        // evidence must be flushed now — otherwise the replacement silently erases
        // the violation and analyze() never sees it.
        if (replaced != null && !replaced.accessedFields.isEmpty()) {
            violations.add(neverValidatedViolation(replaced));
        }
    }

    /**
     * Call when reading a field whose value was obtained during an optimistic read.
     *
     * @param fieldName descriptive name for the data being read (for reports)
     *
     * @param lock the lock
     * @param stamp the stamp
     * @param thread the thread
     */
    public void recordDataAccessed(Object lock, long stamp, Thread thread, String fieldName) {
        if (lock == null || thread == null) return;
        OptimisticRead read = pendingReads.get(key(lock, thread));
        if (read != null && read.stamp == stamp && fieldName != null) {
            read.accessedFields.add(fieldName);
        }
    }

    /**
     * Call immediately after {@code lock.validate(stamp)}.
     *
     * @param result the boolean returned by {@code validate()}
     *
     * @param lock the lock
     * @param stamp the stamp
     * @param thread the thread
     */
    public void recordValidateCalled(Object lock, long stamp, boolean result, Thread thread) {
        if (lock == null || thread == null) return;
        String k = key(lock, thread);
        OptimisticRead read = pendingReads.get(k);
        if (read == null) return;
        // A validate() for some other stamp does not validate the pending read —
        // leave it pending so its missing validation is still reported at analysis
        // time (removing it here silently discarded the evidence).
        if (read.stamp != stamp) return;
        pendingReads.remove(k);
        if (!result && !read.accessedFields.isEmpty()) {
            violations.add(String.format(
                "Thread '%s': data accessed (%s) during optimistic read but stamp validation FAILED — "
                + "value is a torn snapshot from a concurrent write",
                read.threadName, String.join(", ", read.accessedFields)));
        }
    }

    /**
     * {@return report of optimistic read validation failures}
     */
    public OptimisticReadValidationReport analyze() {
        OptimisticReadValidationReport r = new OptimisticReadValidationReport();
        // reads still pending at analysis time were never validated
        for (OptimisticRead read : pendingReads.values()) {
            if (!read.accessedFields.isEmpty()) {
                r.violations.add(neverValidatedViolation(read));
            }
        }
        r.violations.addAll(violations);
        return r;
    }

    private static String neverValidatedViolation(OptimisticRead read) {
        return String.format(
            "Thread '%s': data accessed (%s) during optimistic read but validate() was never called",
            read.threadName, String.join(", ", read.accessedFields));
    }

    /** Report produced by {@link #analyze()}. */
    public static class OptimisticReadValidationReport {
        final List<String> violations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("OPTIMISTIC READ VALIDATION ISSUE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: StampedLock's optimistic read acquires no lock, so a concurrent writer may update shared fields\n" +
                       "       between the read and the validate() call. The read values are then a torn snapshot — some fields\n" +
                       "       from before the write and some from after — producing silently wrong computation results.\n" +
                       "  Fix: always call lock.validate(stamp) before using optimistically-read data;\n" +
                       "       if validation fails, re-read under a full read lock:\n" +
                       "       long stamp = lock.tryOptimisticRead(); int v = field;\n" +
                       "       if (!lock.validate(stamp)) { stamp = lock.readLock(); try { v = field; } finally { lock.unlockRead(stamp); } }");
            return sb.toString();
        }
    }
}
