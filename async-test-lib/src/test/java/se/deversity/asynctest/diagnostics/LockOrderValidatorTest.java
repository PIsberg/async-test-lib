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

    /**
     * Two locks whose identity hashes collide are still two locks. Keyed by class name plus
     * identity hash, one thread nesting {@code first} inside {@code a} and another nesting
     * {@code a} inside {@code second} read as one pair taken both ways round: a lock-order
     * inversion and a deadlock cycle that no two threads could ever form.
     */
    @Test
    void locksSharingAnIdentityHashAreNotMergedIntoAnInversion() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        java.util.List<Object> colliding = IdentityCollisions.pair(Object::new);
        Object first = colliding.get(0);
        Object second = colliding.get(1);
        Object a = new Object();

        Thread t1 = new Thread(() -> {
            validator.recordLockAcquisition(a);
            validator.recordLockAcquisition(first);
            validator.recordLockRelease(first);
            validator.recordLockRelease(a);
        });
        Thread t2 = new Thread(() -> {
            validator.recordLockAcquisition(second);
            validator.recordLockAcquisition(a);
            validator.recordLockRelease(a);
            validator.recordLockRelease(second);
        });
        t1.start();
        t1.join();
        t2.start();
        t2.join();

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues(),
                "a -> first and second -> a involve three distinct locks and cannot deadlock: " + report);
    }

    /** The same shape on one lock is the real inversion, and still fires. */
    @Test
    void theSameLockTakenBothWaysRoundStillFires() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object shared = new Object();
        Object a = new Object();

        Thread t1 = new Thread(() -> {
            validator.recordLockAcquisition(a);
            validator.recordLockAcquisition(shared);
            validator.recordLockRelease(shared);
            validator.recordLockRelease(a);
        });
        Thread t2 = new Thread(() -> {
            validator.recordLockAcquisition(shared);
            validator.recordLockAcquisition(a);
            validator.recordLockRelease(a);
            validator.recordLockRelease(shared);
        });
        t1.start();
        t1.join();
        t2.start();
        t2.join();

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertEquals(1, report.inconsistentOrderings.size(), report.toString());
        assertFalse(report.potentialDeadlockCycles.isEmpty(), report.toString());
    }
}
