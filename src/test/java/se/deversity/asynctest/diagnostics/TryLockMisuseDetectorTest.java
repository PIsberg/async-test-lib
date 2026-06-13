package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class TryLockMisuseDetectorTest {

    @Test
    void cleanWhenSuccessfulTryLockAndUnlock() {
        var d = new TryLockMisuseDetector();
        var lock = new ReentrantLock();
        d.recordTryLockResult(lock, "my-lock", true, Thread.currentThread());
        d.recordUnlock(lock, "my-lock", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void violationWhenUnlockCalledOnFailedTryLock() {
        var d = new TryLockMisuseDetector();
        var lock = new ReentrantLock();
        d.recordTryLockResult(lock, "my-lock", false, Thread.currentThread());
        d.recordUnlock(lock, "my-lock", Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("my-lock"));
        assertTrue(msg.contains("tryLock misuse detected"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("TryLockMisuse", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }
}
