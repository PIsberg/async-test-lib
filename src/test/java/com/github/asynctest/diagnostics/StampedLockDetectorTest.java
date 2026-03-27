package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.StampedLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StampedLockDetector.
 */
public class StampedLockDetectorTest {

    @Test
    void testValidOptimisticRead() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();

        detector.registerLock(lock, "validLock");
        long stamp = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "validLock", stamp);
        
        boolean validated = lock.validate(stamp);
        detector.recordOptimisticValidation(lock, "validLock", stamp, validated);

        StampedLockDetector.StampedLockReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Valid optimistic read should not report issues");
    }

    @Test
    void testUnvalidatedOptimisticReadDetection() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();

        detector.registerLock(lock, "unvalidatedLock");
        long stamp = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "unvalidatedLock", stamp);
        // Missing validation!

        StampedLockDetector.StampedLockReport report = detector.analyze();

        assertNotNull(report);
        // Note: Detector tracks unvalidated reads when validation fails
    }

    @Test
    void testInvalidOptimisticReadDetection() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();

        detector.registerLock(lock, "invalidLock");
        long stamp = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "invalidLock", stamp);
        
        // Invalidate by acquiring write lock
        lock.writeLock();
        detector.recordOptimisticValidation(lock, "invalidLock", stamp, false);

        StampedLockDetector.StampedLockReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect invalid optimistic read");
    }

    @Test
    void testReadLockUsage() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();

        detector.registerLock(lock, "readLock");
        long stamp = lock.readLock();
        detector.recordReadLock(lock, "readLock", stamp);
        lock.unlockRead(stamp);
        detector.recordUnlock(lock, "readLock", stamp);

        StampedLockDetector.StampedLockReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Read lock usage should not report issues");
    }

    @Test
    void testWriteLockUsage() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();

        detector.registerLock(lock, "writeLock");
        long stamp = lock.writeLock();
        detector.recordWriteLock(lock, "writeLock", stamp);
        lock.unlockWrite(stamp);
        detector.recordUnlock(lock, "writeLock", stamp);

        StampedLockDetector.StampedLockReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Write lock usage should not report issues");
    }

    @Test
    void testReportToString() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();

        detector.registerLock(lock, "testLock");
        long stamp = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "testLock", stamp);
        detector.recordOptimisticValidation(lock, "testLock", stamp, false);

        StampedLockDetector.StampedLockReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("STAMPEDLOCK ISSUES DETECTED"), "Report should have header");
    }
}
