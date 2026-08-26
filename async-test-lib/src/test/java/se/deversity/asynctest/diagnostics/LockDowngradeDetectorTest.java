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
        // One line per lock, carrying the count. It used to be one line per occurrence, which
        // made a stress test print the same sentence once per body execution (issue #351).
        assertEquals(1, report.upgradeAttempts.size(),
                "identical upgrades on one lock collapse to one line: " + report.upgradeAttempts);
        assertTrue(report.upgradeAttempts.get(0).contains("(x3)"),
                "all 3 attempts must still be counted: " + report.upgradeAttempts.get(0));
    }

    // -------------------------------------------------------------------------
    // The unsafe downgrade — issue #355
    // -------------------------------------------------------------------------

    /**
     * The gap the detector is named for: the write lock released before the read lock is taken,
     * with another thread getting the write lock in between. Driven deterministically here -
     * the second thread's write acquire is recorded while the first thread's gap is open - so
     * the assertion measures the rule and not the scheduler.
     */
    @Test
    void detectsTheUnsafeDowngradeWhenAWriterGetsIntoTheGap() throws Exception {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        detector.recordWriteLockAcquired(lock, "store");
        detector.recordWriteLockReleased(lock, "store");     // gap opens here

        Thread interloper = new Thread(() -> {
            detector.recordWriteLockAcquired(lock, "store"); // ... and is used
            detector.recordWriteLockReleased(lock, "store");
        }, "interloper");
        interloper.start();
        interloper.join(5_000);

        detector.recordReadLockAcquired(lock, "store");      // gap closes
        detector.recordReadLockReleased(lock, "store");

        LockDowngradeDetector.LockDowngradeReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the write lock was released before the read lock was taken and another thread "
                        + "wrote in between; the read need not return what was written");
        assertTrue(report.toString().contains("unsafe downgrade"),
                "the finding must name what it is: " + report);
        assertTrue(report.toString().contains("store"),
                "the finding must name the lock: " + report);
    }

    /**
     * The correct downgrade never opens a gap, so no amount of contention can make it a finding.
     */
    @Test
    void theCorrectDowngradeStaysSilentUnderContention() throws Exception {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        detector.recordWriteLockAcquired(lock, "store");
        detector.recordReadLockAcquired(lock, "store");      // read taken while write is held
        detector.recordWriteLockReleased(lock, "store");

        Thread interloper = new Thread(() -> {
            detector.recordWriteLockAcquired(lock, "store");
            detector.recordWriteLockReleased(lock, "store");
        }, "interloper");
        interloper.start();
        interloper.join(5_000);

        detector.recordReadLockReleased(lock, "store");

        assertFalse(detector.analyze().hasIssues(),
                "there is no moment at which this thread holds neither lock: "
                        + detector.analyze());
    }

    /**
     * The false positive this rule is built to avoid. A thread that writes, releases, and later
     * reads something unrelated produces the same recorded shape as the unsafe downgrade, and
     * nothing in the records tells them apart. Reporting on the shape alone would flag correct
     * code, so the shape alone is not a finding: it needs a writer observed inside the gap.
     */
    @Test
    void theDowngradeShapeAloneIsNotAFinding() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        lock.writeLock().lock();
        detector.recordWriteLockAcquired(lock, "store");
        lock.writeLock().unlock();
        detector.recordWriteLockReleased(lock, "store");

        lock.readLock().lock();
        detector.recordReadLockAcquired(lock, "store");
        lock.readLock().unlock();
        detector.recordReadLockReleased(lock, "store");

        assertFalse(detector.analyze().hasIssues(),
                "nobody was observed taking the write lock in the gap, so this run has no "
                        + "evidence that anything was lost. See issue #355 for the trade: "
                        + "a false negative here is preferred to a finding on correct code");
    }

    /**
     * The gap has to be closed by the read acquire itself. A thread that does other recorded
     * work on the lock in between has done something the records cannot tie back to the write.
     */
    @Test
    void aReadThatIsNotTheNextEventDoesNotCloseAGap() throws Exception {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        detector.recordWriteLockAcquired(lock, "store");
        detector.recordWriteLockReleased(lock, "store");

        Thread interloper = new Thread(() -> {
            detector.recordWriteLockAcquired(lock, "store");
            detector.recordWriteLockReleased(lock, "store");
        }, "interloper");
        interloper.start();
        interloper.join(5_000);

        // Unrelated recorded work on the same lock, from the same thread, before the read.
        detector.recordWriteLockAcquired(lock, "store");
        detector.recordWriteLockReleased(lock, "store");
        detector.recordReadLockAcquired(lock, "store");
        detector.recordReadLockReleased(lock, "store");

        assertFalse(detector.analyze().unsafeDowngrades.stream()
                        .anyMatch(finding -> finding.contains("unsafe downgrade")),
                "the first gap was closed by a write acquire, not by the read: " + detector.analyze());
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
