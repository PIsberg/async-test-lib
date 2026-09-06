package se.deversity.asynctest.diagnostics;

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
    void aFailedValidationFollowedByAReadLockIsTheCorrectIdiom() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();

        detector.registerLock(lock, "fallbackLock");
        long optimistic = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "fallbackLock", optimistic);

        // A writer intervened, so validate() returns false. This is the case StampedLock is
        // designed around, and the documented answer is to take the read lock.
        detector.recordOptimisticValidation(lock, "fallbackLock", optimistic, false);
        long readStamp = lock.readLock();
        detector.recordReadLock(lock, "fallbackLock", readStamp);
        lock.unlockRead(readStamp);
        detector.recordUnlock(lock, "fallbackLock", readStamp);

        StampedLockDetector.StampedLockReport report = detector.analyze();

        assertFalse(report.hasIssues(),
            "validate() returning false and the caller falling back to readLock() is the "
                + "canonical StampedLock idiom, so the detector must stay silent: " + report);
    }

    @Test
    void aFailedValidationFollowedByARetriedOptimisticReadIsTheCorrectIdiom() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();

        detector.registerLock(lock, "retryLock");
        long first = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "retryLock", first);
        detector.recordOptimisticValidation(lock, "retryLock", first, false);

        long second = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "retryLock", second);
        detector.recordOptimisticValidation(lock, "retryLock", second, true);

        StampedLockDetector.StampedLockReport report = detector.analyze();

        assertFalse(report.hasIssues(),
            "retrying the optimistic read after a failed validation is the other half of the "
                + "documented idiom: " + report);
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

    @Test
    void anOptimisticReadThatIsNeverValidatedIsReported() {
        StampedLockDetector detector = new StampedLockDetector();
        java.util.concurrent.locks.StampedLock lock = new java.util.concurrent.locks.StampedLock();
        detector.registerLock(lock, "neverValidated");
        long stamp = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "neverValidated", stamp);
        // no recordOptimisticValidation: the value read is used without validate()

        StampedLockDetector.StampedLockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
            "The class promises to detect an optimistic read without validate(); the counters "
                + "that would show it were never consulted by analyze(): " + report);
        assertTrue(report.toString().contains("neverValidated"), report.toString());
    }
}
