package com.github.asynctest.diagnostics;

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
