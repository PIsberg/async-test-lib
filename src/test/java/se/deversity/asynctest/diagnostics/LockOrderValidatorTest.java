package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LockOrderValidatorTest {

    @Test
    void noRecordingsReturnNoIssues() {
        LockOrderValidator validator = new LockOrderValidator();
        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues());
    }

    @Test
    void consistentLockOrderNoIssues() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object lockA = new Object();
        Object lockB = new Object();

        Thread t1 = new Thread(() -> {
            validator.recordLockAcquisition(lockA);
            validator.recordLockAcquisition(lockB);
            validator.recordLockRelease(lockB);
            validator.recordLockRelease(lockA);
        });
        Thread t2 = new Thread(() -> {
            validator.recordLockAcquisition(lockA);
            validator.recordLockAcquisition(lockB);
            validator.recordLockRelease(lockB);
            validator.recordLockRelease(lockA);
        });

        t1.start();
        t1.join();
        t2.start();
        t2.join();

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues());
    }

    @Test
    void inconsistentOrderDetected() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object lockA = new Object();
        Object lockB = new Object();

        Thread t1 = new Thread(() -> {
            validator.recordLockAcquisition(lockA);
            validator.recordLockAcquisition(lockB);
            validator.recordLockRelease(lockB);
            validator.recordLockRelease(lockA);
        });
        Thread t2 = new Thread(() -> {
            validator.recordLockAcquisition(lockB);
            validator.recordLockAcquisition(lockA);
            validator.recordLockRelease(lockA);
            validator.recordLockRelease(lockB);
        });

        t1.start();
        t1.join();
        t2.start();
        t2.join();

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertTrue(report.hasIssues());
        assertFalse(report.inconsistentOrderings.isEmpty());
    }

    @Test
    void nullLockHandled() {
        LockOrderValidator validator = new LockOrderValidator();
        assertDoesNotThrow(() -> validator.recordLockAcquisition(null));
        assertDoesNotThrow(() -> validator.recordLockRelease(null));
    }

    @Test
    void reportHasIssuesFalseWhenEmpty() {
        LockOrderValidator validator = new LockOrderValidator();
        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues());
        assertTrue(report.inconsistentOrderings.isEmpty());
        assertTrue(report.potentialDeadlockCycles.isEmpty());
    }

    @Test
    void reportToStringWithIssues() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object lockA = new Object();
        Object lockB = new Object();

        Thread t1 = new Thread(() -> {
            validator.recordLockAcquisition(lockA);
            validator.recordLockAcquisition(lockB);
            validator.recordLockRelease(lockB);
            validator.recordLockRelease(lockA);
        });
        Thread t2 = new Thread(() -> {
            validator.recordLockAcquisition(lockB);
            validator.recordLockAcquisition(lockA);
            validator.recordLockRelease(lockA);
            validator.recordLockRelease(lockB);
        });

        t1.start();
        t1.join();
        t2.start();
        t2.join();

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void resetClearsState() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object lockA = new Object();
        Object lockB = new Object();

        Thread t1 = new Thread(() -> {
            validator.recordLockAcquisition(lockA);
            validator.recordLockAcquisition(lockB);
            validator.recordLockRelease(lockB);
            validator.recordLockRelease(lockA);
        });
        Thread t2 = new Thread(() -> {
            validator.recordLockAcquisition(lockB);
            validator.recordLockAcquisition(lockA);
            validator.recordLockRelease(lockA);
            validator.recordLockRelease(lockB);
        });

        t1.start();
        t1.join();
        t2.start();
        t2.join();

        assertTrue(validator.validateLockOrder().hasIssues());
        validator.reset();
        assertFalse(validator.validateLockOrder().hasIssues());
    }
}
