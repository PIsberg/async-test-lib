package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.StampedLock;
import static org.junit.jupiter.api.Assertions.*;

public class OptimisticReadValidationDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new OptimisticReadValidationDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenValidationSucceeds() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        d.recordValidateCalled(lock, stamp, true, t); // validation passed
        assertFalse(d.analyze().hasIssues());
    }

    /**
     * The class javadoc's own idiom, with a writer landing between the read and the validate: the
     * validation fails, the optimistic values are thrown away, and the value used is re-read under
     * the read lock. Correct code, so no finding.
     */
    @Test
    void theValidateAndRetryIdiomIsSilentWhenValidationFails() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        int[] shared = {1};
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        int x = shared[0];
        d.recordDataAccessed(lock, stamp, t, "sharedX");

        long write = lock.writeLock();                 // the concurrent writer
        shared[0] = 2;
        lock.unlockWrite(write);

        boolean valid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, valid, t);
        assertFalse(valid, "premise: the write invalidated the optimistic stamp");
        if (!valid) {
            long read = lock.readLock();
            try {
                x = shared[0];
            } finally {
                lock.unlockRead(read);
            }
        }

        assertEquals(2, x, "the value used is the one re-read under the lock");
        assertFalse(d.analyze().hasIssues(),
                "a failed validate() is the idiom telling the caller to retry, not a torn read "
                        + "that was used: " + d.analyze().violations);
    }

    /** Two locks read optimistically in turn, each validated: correct, even if their hashes collide. */
    @Test
    void twoLocksWhoseIdentityHashesCollideAreNotMerged() {
        StampedLock[] pair = collidingLocks();
        org.junit.jupiter.api.Assumptions.assumeTrue(pair != null,
                "no identity-hash collision found among the locks allocated");
        var d = new OptimisticReadValidationDetector();
        Thread t = Thread.currentThread();

        long first = pair[0].tryOptimisticRead();
        d.recordOptimisticReadStarted(pair[0], first, t);
        d.recordDataAccessed(pair[0], first, t, "a");
        long second = pair[1].tryOptimisticRead();
        d.recordOptimisticReadStarted(pair[1], second, t);
        d.recordDataAccessed(pair[1], second, t, "b");
        d.recordValidateCalled(pair[1], second, true, t);
        d.recordValidateCalled(pair[0], first, true, t);

        assertFalse(d.analyze().hasIssues(),
                "keyed by identity hash, the second lock's read replaced the first's and reported "
                        + "it never validated: " + d.analyze().violations);
    }

    /** {@return two distinct locks with the same identity hash, or null if none turned up} */
    private static StampedLock[] collidingLocks() {
        java.util.Map<Integer, StampedLock> seen = new java.util.HashMap<>();
        for (int i = 0; i < 2_000_000; i++) {
            StampedLock lock = new StampedLock();
            StampedLock earlier = seen.putIfAbsent(System.identityHashCode(lock), lock);
            if (earlier != null) {
                return new StampedLock[] {earlier, lock};
            }
        }
        return null;
    }

    @Test
    void testDetectsDataAccessedWithoutValidation() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedY");
        // validate never called — detected at analyze() time
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("sharedY"));
        assertTrue(d.analyze().violations.get(0).contains("never called"));
    }

    @Test
    void testNoIssueWhenNoDataAccessedBeforeFailedValidation() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        // no data accessed
        d.recordValidateCalled(lock, stamp, false, t); // failed but no data was read
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new OptimisticReadValidationDetector();
        assertDoesNotThrow(() -> {
            d.recordOptimisticReadStarted(null, 0L, Thread.currentThread());
            d.recordOptimisticReadStarted(new StampedLock(), 0L, null);
            d.recordDataAccessed(null, 0L, Thread.currentThread(), "x");
            d.recordValidateCalled(null, 0L, false, Thread.currentThread());
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testUnvalidatedReadSurvivesANewOptimisticReadOnSameLockAndThread() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        // First optimistic read: data accessed, validate() never called — a real bug.
        d.recordOptimisticReadStarted(lock, 1L, t);
        d.recordDataAccessed(lock, 1L, t, "sharedX");

        // Second optimistic read on the same lock from the same thread, done correctly.
        d.recordOptimisticReadStarted(lock, 2L, t);
        d.recordDataAccessed(lock, 2L, t, "sharedY");
        d.recordValidateCalled(lock, 2L, true, t);

        var report = d.analyze();
        assertTrue(report.hasIssues(),
            "the first read's missing validate() must still be reported after a later, correct read");
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("sharedX") && v.contains("never called")),
            "violation should identify the unvalidated first read: " + report.violations);
    }

    @Test
    void testValidateWithWrongStampDoesNotDiscardThePendingRead() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        d.recordOptimisticReadStarted(lock, 1L, t);
        d.recordDataAccessed(lock, 1L, t, "sharedZ");
        // validate() called with a stamp from some other read — the pending read
        // above is still unvalidated and must not be silently forgotten.
        d.recordValidateCalled(lock, 99L, true, t);

        var report = d.analyze();
        assertTrue(report.hasIssues(),
            "a stamp-mismatched validate() must not erase the unvalidated read");
        assertTrue(report.violations.get(0).contains("sharedZ"));
        assertTrue(report.violations.get(0).contains("never called"));
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "field");
        d.recordValidateCalled(lock, stamp, false, t);
        String s = d.analyze().toString();
        assertTrue(s.contains("OPTIMISTIC READ VALIDATION"));
        assertTrue(s.contains("Fix"));
    }
}
