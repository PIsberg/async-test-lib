package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects incorrect usage of {@link java.util.concurrent.locks.StampedLock} optimistic reads:
 * reading data after {@code tryOptimisticRead()} without calling {@code validate(stamp)}.
 *
 * <p>An optimistic read stamp is only valid if no write lock was acquired between
 * {@code tryOptimisticRead()} and {@code validate(stamp)}. Using data from an invalidated
 * optimistic read silently introduces torn-snapshot data corruption.
 *
 * <p>A validation that fails is not a finding. Under contention it is the normal path of the
 * idiom below: the caller learns the values may be torn, drops them and re-reads under the read
 * lock or retries. Using the torn values anyway is the finding, and it is visible only where the
 * use is recorded with {@link #recordValuesUsed}: the stamp passed there is the one the used values
 * were read under, so a use after a re-read under the read lock names the read-lock stamp and stays
 * silent, while a use under the failed optimistic stamp is reported. A read that is never validated
 * is reported whether or not its use is recorded.
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
 * boolean valid = lock.validate(stamp);
 * mon.recordValidateCalled(lock, stamp, valid, Thread.currentThread());
 * if (!valid) {
 *     stamp = lock.readLock();                     // re-read under a full lock
 *     try { x = sharedX; } finally { lock.unlockRead(stamp); }
 * }
 * mon.recordValuesUsed(lock, stamp, Thread.currentThread());
 * use(x);
 * }</pre>
 */
public class OptimisticReadValidationDetector {

    /** Where a read stands: not yet validated, failed its validation, or already reported as used. */
    private enum State { PENDING, FAILED, REPORTED }

    private static class OptimisticRead {
        final long         stamp;
        final String       threadName;
        final List<String> accessedFields = new ArrayList<>();
        volatile State     state          = State.PENDING;

        OptimisticRead(long stamp, String threadName) {
            this.stamp = stamp;
            this.threadName = threadName;
        }
    }

    /**
     * The lock by identity and the reading thread. The lock used to be keyed by its identity hash,
     * which two live locks can share, so one lock's optimistic read replaced another's.
     */
    private record ReadKey(IdentityKey lock, long threadId) { }

    /**
     * The latest read per (lock, thread) that is still pending or failed its validation. A read
     * that validated is removed; a failed one stays so a later use of its values can be matched.
     */
    private final Map<ReadKey, OptimisticRead> reads      = new ConcurrentHashMap<>();
    private final List<String>                 violations = new CopyOnWriteArrayList<>();

    private static ReadKey key(Object lock, Thread thread) {
        return new ReadKey(new IdentityKey(lock), thread.threadId());
    }

    /**
     * Call immediately after {@code StampedLock.tryOptimisticRead()}.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param stamp the stamp returned by the {@code StampedLock} operation
     * @param thread the thread performing the operation
     */
    public void recordOptimisticReadStarted(Object lock, long stamp, Thread thread) {
        if (lock == null || thread == null) return;
        OptimisticRead replaced =
            reads.put(key(lock, thread), new OptimisticRead(stamp, thread.getName()));
        // A new optimistic read replaces the pending one for this (lock, thread).
        // If the replaced read had accessed data without ever being validated, that
        // evidence must be flushed now — otherwise the replacement silently erases
        // the violation and analyze() never sees it.
        if (replaced != null && replaced.state == State.PENDING && !replaced.accessedFields.isEmpty()) {
            violations.add(neverValidatedViolation(replaced));
        }
    }

    /**
     * Call when reading a field whose value was obtained during an optimistic read.
     *
     * @param fieldName descriptive name for the data being read (for reports)
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param stamp the stamp returned by the {@code StampedLock} operation
     * @param thread the thread performing the operation
     */
    public void recordDataAccessed(Object lock, long stamp, Thread thread, String fieldName) {
        if (lock == null || thread == null) return;
        OptimisticRead read = reads.get(key(lock, thread));
        if (read != null && read.stamp == stamp && read.state == State.PENDING && fieldName != null) {
            read.accessedFields.add(fieldName);
        }
    }

    /**
     * Call immediately after {@code lock.validate(stamp)}.
     *
     * @param result the boolean returned by {@code validate()}; either value closes the read, since a
     *               false one is the caller's cue to re-read under a lock. After a false one,
     *               {@link #recordValuesUsed} with this stamp reports the torn values being used
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param stamp the stamp returned by the {@code StampedLock} operation
     * @param thread the thread performing the operation
     */
    public void recordValidateCalled(Object lock, long stamp, boolean result, Thread thread) {
        if (lock == null || thread == null) return;
        ReadKey k = key(lock, thread);
        OptimisticRead read = reads.get(k);
        if (read == null) return;
        // A validate() for some other stamp does not validate the pending read —
        // leave it pending so its missing validation is still reported at analysis
        // time (removing it here silently discarded the evidence).
        if (read.stamp != stamp || read.state != State.PENDING) return;
        // Validated either way. A false result is the idiom's retry signal, not a use of the
        // torn values, so it is not reported (it used to be, which fired on correct code). The
        // failed read is kept so that recordValuesUsed can still catch its values being used.
        if (result) {
            reads.remove(k);
        } else {
            read.state = State.FAILED;
        }
    }

    /**
     * Call where the values read under {@code stamp} are used, after the {@code validate()} that
     * was meant to gate them. Pass the stamp the used values were read under: after a failed
     * validation that is the read-lock stamp of the re-read, so the retry idiom stays silent,
     * while using the optimistic values themselves is reported. A use before any
     * {@code validate()} adds nothing to the never-validated finding already reported for it.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param stamp the stamp the used values were read under
     * @param thread the thread performing the operation
     * @since 1.12.3
     */
    public void recordValuesUsed(Object lock, long stamp, Thread thread) {
        if (lock == null || thread == null) return;
        OptimisticRead read = reads.get(key(lock, thread));
        if (read == null || read.stamp != stamp || read.state != State.FAILED) return;
        read.state = State.REPORTED; // one finding per read, however often its values are used
        violations.add(usedAfterFailedValidation(read));
    }

    /**
     * {@return report of optimistic read validation failures}
     */
    public OptimisticReadValidationReport analyze() {
        OptimisticReadValidationReport r = new OptimisticReadValidationReport();
        // reads still pending at analysis time were never validated
        for (OptimisticRead read : reads.values()) {
            if (read.state == State.PENDING && !read.accessedFields.isEmpty()) {
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

    private static String usedAfterFailedValidation(OptimisticRead read) {
        return String.format(
            "Thread '%s': values read optimistically (%s) were used after validate() returned false,"
                + " so they may be torn",
            read.threadName,
            read.accessedFields.isEmpty() ? "no fields recorded" : String.join(", ", read.accessedFields));
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
            sb.append("""
  Why: StampedLock's optimistic read acquires no lock, so a concurrent writer may update shared fields
       between the read and the validate() call. The read values are then a torn snapshot — some fields
       from before the write and some from after — producing silently wrong computation results.
  Fix: always call lock.validate(stamp) before using optimistically-read data;
       if validation fails, re-read under a full read lock:
       long stamp = lock.tryOptimisticRead(); int v = field;
       if (!lock.validate(stamp)) { stamp = lock.readLock(); try { v = field; } finally { lock.unlockRead(stamp); } }\
""");
            return sb.toString();
        }
    }
}
