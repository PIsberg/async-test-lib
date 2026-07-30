package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import static org.junit.jupiter.api.Assertions.*;

class LockUpgradeDeadlockDetectorTest {

    @Test
    void cleanWhenNoUpgrade() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
        d.recordReadLockReleased(lock, Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void deadlockWhenUpgradeAttempted() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("my-lock"));
        assertTrue(msg.contains("upgrade attempt detected"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("LockUpgradeDeadlock", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void nullLockAndThreadAreIgnored() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(null, "my-lock", Thread.currentThread());
        d.recordReadLockAcquired(lock, "my-lock", null);
        d.recordReadLockReleased(null, Thread.currentThread());
        d.recordReadLockReleased(lock, null);
        d.recordWriteLockAcquisitionAttempt(null, "my-lock", Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void releaseWithoutPriorAcquireIsIgnored() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockReleased(lock, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void writeAttemptWithoutHoldingReadLockIsIgnored() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingLockNameFallsBackToIdentity() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, null, Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("ReentrantReadWriteLock@"));
    }

    @Test
    void reportToStringReflectsState() {
        var clean = new LockUpgradeDeadlockDetector().analyze();
        assertEquals("LOCK UPGRADE DEADLOCK — clean", clean.toString());

        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("LOCK UPGRADE DEADLOCK DETECTED"));
        assertTrue(rendered.contains("my-lock"));
    }
}
