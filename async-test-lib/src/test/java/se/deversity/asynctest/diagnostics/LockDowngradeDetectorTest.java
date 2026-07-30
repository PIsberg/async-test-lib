package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

public class LockDowngradeDetectorTest {

    @Test
    void testNoIssueWithValidDowngrade() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        // Valid downgrade: write → read (while holding write) → release write
        lock.writeLock().lock();
        detector.recordWriteLockAcquired(lock, "myLock");
        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, "myLock");
        lock.writeLock().unlock();
        detector.recordWriteLockReleased(lock, "myLock");
        lock.readLock().unlock();
        detector.recordReadLockReleased(lock, "myLock");

        assertFalse(detector.analyze().hasIssues(), "Valid downgrade should not report issues");
    }

    @Test
    void testNoIssueWithNormalWriteLock() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        lock.writeLock().lock();
        detector.recordWriteLockAcquired(lock, "myLock");
        lock.writeLock().unlock();
        detector.recordWriteLockReleased(lock, "myLock");

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testDetectsReadToWriteUpgradeAttempt() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        // Simulate: acquire read lock, then try to acquire write (upgrade — not supported)
        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, "buggyLock");
        try {
            // Record the upgrade attempt WITHOUT actually calling writeLock().lock()
            // (which would deadlock in the real lock)
            detector.recordWriteLockAcquired(lock, "buggyLock");
        } finally {
            lock.readLock().unlock();
            detector.recordReadLockReleased(lock, "buggyLock");
        }

        LockDowngradeDetector.LockDowngradeReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect read→write upgrade attempt");
        assertFalse(report.upgradeAttempts.isEmpty());
        assertTrue(report.upgradeAttempts.get(0).contains("buggyLock"));
        assertTrue(report.upgradeAttempts.get(0).contains("read→write upgrade"));
    }

    @Test
    void testMultipleUpgradeAttemptsAllRecorded() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        for (int i = 0; i < 3; i++) {
            lock.readLock().lock();
            detector.recordReadLockAcquired(lock, "lock");
            detector.recordWriteLockAcquired(lock, "lock");
            detector.recordWriteLockReleased(lock, "lock");
            lock.readLock().unlock();
            detector.recordReadLockReleased(lock, "lock");
        }

        LockDowngradeDetector.LockDowngradeReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(3, report.upgradeAttempts.size(), "All 3 upgrade attempts should be recorded");
    }

    @Test
    void testNullSafety() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        assertDoesNotThrow(() -> {
            detector.recordReadLockAcquired(null, "x");
            detector.recordReadLockReleased(null, "x");
            detector.recordWriteLockAcquired(null, "x");
            detector.recordWriteLockReleased(null, "x");
        });
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testAutoNameFromIdentityHash() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, null);
        detector.recordWriteLockAcquired(lock, null);
        lock.readLock().unlock();
        detector.recordReadLockReleased(lock, null);

        LockDowngradeDetector.LockDowngradeReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.upgradeAttempts.get(0).contains("rwlock@"));
    }

    @Test
    void testReleasedReadLockDoesNotTriggerUpgrade() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        // Acquire and release read, then acquire write — should be fine
        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, "ok-lock");
        lock.readLock().unlock();
        detector.recordReadLockReleased(lock, "ok-lock");

        lock.writeLock().lock();
        detector.recordWriteLockAcquired(lock, "ok-lock");
        lock.writeLock().unlock();
        detector.recordWriteLockReleased(lock, "ok-lock");

        assertFalse(detector.analyze().hasIssues(), "Released read lock should not flag write acquire");
    }

    @Test
    void testDetectsUpgradeAttemptAfterValidDowngrade() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        // Correct downgrade: write → read (while holding write) → release write.
        lock.writeLock().lock();
        detector.recordWriteLockAcquired(lock, "dgLock");
        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, "dgLock");
        lock.writeLock().unlock();
        detector.recordWriteLockReleased(lock, "dgLock");

        // Still holding ONLY the read lock — attempting the write lock now is the
        // classic upgrade deadlock. (Not actually calling writeLock().lock(),
        // which would deadlock this test.)
        detector.recordWriteLockAcquired(lock, "dgLock");

        lock.readLock().unlock();
        detector.recordReadLockReleased(lock, "dgLock");

        LockDowngradeDetector.LockDowngradeReport report = detector.analyze();
        assertTrue(report.hasIssues(),
            "re-acquiring write while holding only the downgraded read lock deadlocks and must be flagged");
        assertTrue(report.upgradeAttempts.get(0).contains("read→write upgrade"));
    }

    @Test
    void testReentrantWriteAcquireWhileHoldingWriteAndReadIsNotFlagged() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        // Holding write + read (mid-downgrade), a reentrant write acquire is legal.
        lock.writeLock().lock();
        detector.recordWriteLockAcquired(lock, "reentrant");
        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, "reentrant");

        lock.writeLock().lock(); // reentrant — succeeds, no deadlock
        detector.recordWriteLockAcquired(lock, "reentrant");

        lock.writeLock().unlock();
        detector.recordWriteLockReleased(lock, "reentrant");
        lock.writeLock().unlock();
        detector.recordWriteLockReleased(lock, "reentrant");
        lock.readLock().unlock();
        detector.recordReadLockReleased(lock, "reentrant");

        assertFalse(detector.analyze().hasIssues(),
            "a reentrant write acquire while the write lock is already held is not an upgrade");
    }

    @Test
    void testReportToStringContainsFixHint() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, "hint-lock");
        detector.recordWriteLockAcquired(lock, "hint-lock");
        lock.readLock().unlock();
        detector.recordReadLockReleased(lock, "hint-lock");

        String str = detector.analyze().toString();
        assertTrue(str.contains("LOCK UPGRADE/DOWNGRADE ISSUES DETECTED"));
        assertTrue(str.contains("Fix"));
        assertTrue(str.contains("downgrade"));
    }
}
