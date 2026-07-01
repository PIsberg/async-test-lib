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

    @Test
    void nullLockAndThreadAreIgnored() {
        var d = new TryLockMisuseDetector();
        var lock = new ReentrantLock();
        d.recordTryLockResult(null, "my-lock", false, Thread.currentThread());
        d.recordTryLockResult(lock, "my-lock", false, null);
        d.recordUnlock(null, "my-lock", Thread.currentThread());
        d.recordUnlock(lock, "my-lock", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void unlockWithoutPriorTryLockResultIsIgnored() {
        var d = new TryLockMisuseDetector();
        var lock = new ReentrantLock();
        d.recordUnlock(lock, "my-lock", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingLockNameFallsBackToIdentity() {
        var d = new TryLockMisuseDetector();
        var lock = new ReentrantLock();
        d.recordTryLockResult(lock, null, false, Thread.currentThread());
        d.recordUnlock(lock, null, Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("Lock@"));
    }

    @Test
    void reportToStringReflectsState() {
        var clean = new TryLockMisuseDetector().analyze();
        assertEquals("TRY LOCK MISUSE — clean", clean.toString());

        var d = new TryLockMisuseDetector();
        var lock = new ReentrantLock();
        d.recordTryLockResult(lock, "my-lock", false, Thread.currentThread());
        d.recordUnlock(lock, "my-lock", Thread.currentThread());
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("TRY LOCK MISUSE DETECTED"));
        assertTrue(rendered.contains("my-lock"));
    }
}
