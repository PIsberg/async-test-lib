package se.deversity.asynctest.diagnostics;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade finding claims a permanent deadlock, so it must hold exactly where
 * {@link ReentrantReadWriteLock} deadlocks, in both directions (#566).
 *
 * <p>The JDK rule is narrow: a thread that holds the read lock and not the write lock blocks
 * forever on {@code writeLock().lock()}. A thread that already holds the write lock may take the
 * read lock and then the write lock again; that is a reentrant acquire and the javadoc's own
 * downgrading example depends on it. And a read lock taken twice is held until it is released
 * twice.
 */
class LockUpgradeDeadlockAccuracyTest {

    @Test
    @DisplayName("a write acquire while holding the write lock is reentrant, not an upgrade")
    void writeHeldReacquireIsLegal() {
        var detector = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        Thread self = Thread.currentThread();

        lock.writeLock().lock();
        try {
            lock.readLock().lock();
            detector.recordReadLockAcquired(lock, "reentrant", self);
            try {
                detector.recordWriteLockAcquisitionAttempt(lock, "reentrant", self);
                // Proves the premise rather than asserting it: this returns at once.
                lock.writeLock().lock();
                lock.writeLock().unlock();
            } finally {
                lock.readLock().unlock();
                detector.recordReadLockReleased(lock, self);
            }
        } finally {
            lock.writeLock().unlock();
        }

        assertFalse(detector.analyze().hasIssues(),
                "the thread held the write lock, so the second write acquire was reentrant and "
                        + "returned; reporting it as a permanent deadlock is a false positive: "
                        + detector.analyze().violations);
    }

    @Test
    @DisplayName("a read lock taken twice and released once is still held at the write attempt")
    void readHoldsAreCounted() {
        var detector = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        Thread self = Thread.currentThread();

        detector.recordReadLockAcquired(lock, "twice", self);
        detector.recordReadLockAcquired(lock, "twice", self);
        detector.recordReadLockReleased(lock, self);
        detector.recordWriteLockAcquisitionAttempt(lock, "twice", self);

        assertTrue(detector.analyze().hasIssues(),
                "one of two read holds is still open, so the write acquire would block forever");
    }

    @Test
    @DisplayName("a real read hold is an upgrade even when the acquire was never recorded")
    void realReadHoldIsAnUpgradeWithoutARecord() {
        var detector = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();

        lock.readLock().lock();
        try {
            detector.recordWriteLockAcquisitionAttempt(lock, "unrecorded", Thread.currentThread());
        } finally {
            lock.readLock().unlock();
        }

        assertTrue(detector.analyze().hasIssues(),
                "the calling thread holds the read lock and not the write lock, which is the JDK's "
                        + "deadlock condition whatever was recorded");
    }

    @Test
    @DisplayName("a real read lock released before the attempt is not an upgrade, whatever was recorded")
    void realLockOverridesAStaleRecord() {
        var detector = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        Thread self = Thread.currentThread();

        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, "released-unrecorded", self);
        lock.readLock().unlock();
        // The release was never recorded, but the write lock is really taken next, and the
        // attempt is recorded while holding it: a thread that holds a lock is asked, not trusted.
        lock.writeLock().lock();
        try {
            detector.recordWriteLockAcquisitionAttempt(lock, "released-unrecorded", self);
        } finally {
            lock.writeLock().unlock();
        }

        assertFalse(detector.analyze().hasIssues(), detector.analyze().violations.toString());
    }

    @Test
    @DisplayName("through the downgrade detector, a write re-acquire during a downgrade is not an upgrade")
    void forwardedReentrantWriteIsNotAnUpgrade() {
        var downgrade = new LockDowngradeDetector();
        var upgrade = new LockUpgradeDeadlockDetector();
        downgrade.deferUpgradeReportingTo(upgrade);
        var lock = new ReentrantReadWriteLock();

        // Recorded only, as the recording API allows: nothing here takes the real lock, so the
        // decision has to come from what the downgrade detector already knows about write holds.
        downgrade.recordWriteLockAcquired(lock, "mid-downgrade");
        downgrade.recordReadLockAcquired(lock, "mid-downgrade");
        downgrade.recordWriteLockAcquired(lock, "mid-downgrade");

        assertFalse(upgrade.analyze().hasIssues(),
                "the thread held the write lock throughout, which LockDowngradeDetector itself "
                        + "calls a legal reentrant acquire: " + upgrade.analyze().violations);
    }

    @Test
    @DisplayName("through the downgrade detector, a real upgrade is still reported")
    void forwardedUpgradeIsStillReported() {
        var downgrade = new LockDowngradeDetector();
        var upgrade = new LockUpgradeDeadlockDetector();
        downgrade.deferUpgradeReportingTo(upgrade);
        var lock = new ReentrantReadWriteLock();

        downgrade.recordReadLockAcquired(lock, "upgraded");
        downgrade.recordWriteLockAcquired(lock, "upgraded");

        assertTrue(upgrade.analyze().hasIssues());
    }
}
