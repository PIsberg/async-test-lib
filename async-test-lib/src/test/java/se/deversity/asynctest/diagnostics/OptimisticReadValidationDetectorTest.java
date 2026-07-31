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

    @Test
    void testDetectsDataUsedAfterFailedValidation() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        d.recordValidateCalled(lock, stamp, false, t); // validation failed — data is torn
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("sharedX"));
        assertTrue(d.analyze().violations.get(0).contains("FAILED"));
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
