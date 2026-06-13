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
}
