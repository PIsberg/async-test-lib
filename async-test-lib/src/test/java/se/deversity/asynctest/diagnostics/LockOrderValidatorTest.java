package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    /** Each colliding lock inverted against {@code a} is its own pair, printed on its own line. */
    @Test
    void twoInversionsOnLocksSharingAnIdentityHashPrintAsTwoLines() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        java.util.List<Object> colliding = IdentityCollisions.pair(Object::new);
        Object a = new Object();
        for (Object lock : colliding) {
            nestOnNewThread(validator, a, lock);
            nestOnNewThread(validator, lock, a);
        }

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertEquals(2, report.inconsistentOrderings.size(),
                "{a, first} and {a, second} are two inversions, even though first and second share "
                        + "an identity hash (#854): " + report);
        assertEquals(3, report.potentialDeadlockCycles.size(),
                "a, first and second are each on a cycle: " + report);
    }

    private static void nestOnNewThread(LockOrderValidator validator, Object outer, Object inner)
            throws InterruptedException {
        Thread thread = new Thread(() -> {
            validator.recordLockAcquisition(outer);
            validator.recordLockAcquisition(inner);
            validator.recordLockRelease(inner);
            validator.recordLockRelease(outer);
        });
        thread.start();
        thread.join();
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

    private static void onNewThread(Runnable body) throws InterruptedException {
        Thread thread = new Thread(body);
        thread.start();
        thread.join();
    }

    private static void nest(LockOrderValidator validator, Object... locks) {
        for (Object lock : locks) {
            validator.recordLockAcquisition(lock);
        }
        for (int i = locks.length - 1; i >= 0; i--) {
            validator.recordLockRelease(locks[i]);
        }
    }

    /**
     * Both orders taken under one outer gate cannot deadlock: whoever holds the gate holds the
     * pair, so the two nestings are never in progress at once (#1242).
     */
    @Test
    void bothOrdersUnderOneGateAreSilent() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object gate = new Object();
        Object a = new Object();
        Object b = new Object();
        onNewThread(() -> nest(validator, gate, a, b));
        onNewThread(() -> nest(validator, gate, b, a));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues(), "a common gate lock orders the pair: " + report);
    }

    /** Two different gates do not order anything: the inversion is real and still fires. */
    @Test
    void bothOrdersUnderDifferentGatesStillFire() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        onNewThread(() -> nest(validator, new Object(), a, b));
        onNewThread(() -> nest(validator, new Object(), b, a));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertEquals(1, report.inconsistentOrderings.size(), report.toString());
        assertFalse(report.potentialDeadlockCycles.isEmpty(), report.toString());
    }

    /** One thread taking both orders in turn cannot deadlock with itself (#1242). */
    @Test
    void oneThreadTakingBothOrdersIsSilent() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        onNewThread(() -> {
            nest(validator, a, b);
            nest(validator, b, a);
        });

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues(), "both nestings came from one thread: " + report);
    }

    /**
     * The tryLock back-off (JCIP listing 13.3): an acquisition that cannot block never waits, so
     * it cannot be the waiting side of a cycle (#1242).
     */
    @Test
    void nonBlockingInnerAcquisitionsAreSilent() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        onNewThread(() -> {
            validator.recordLockAcquisition(a);
            validator.recordLockAcquisition(b, false);
            validator.recordLockRelease(b);
            validator.recordLockRelease(a);
        });
        onNewThread(() -> {
            validator.recordLockAcquisition(b);
            validator.recordLockAcquisition(a, false);
            validator.recordLockRelease(a);
            validator.recordLockRelease(b);
        });

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues(), "neither inner acquisition could wait: " + report);
    }

    /**
     * A lock taken without blocking is still held: a blocking acquisition nested inside it is an
     * edge out of it, so the inversion against a thread that blocks the other way still fires.
     */
    @Test
    void aBlockingAcquisitionInsideATryLockedLockStillFires() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        onNewThread(() -> {
            validator.recordLockAcquisition(a, false);
            validator.recordLockAcquisition(b);
            validator.recordLockRelease(b);
            validator.recordLockRelease(a);
        });
        onNewThread(() -> nest(validator, b, a));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertEquals(1, report.inconsistentOrderings.size(), report.toString());
    }

    /**
     * A reentrant hold released once is still held: the inversion behind it is as real as without
     * the reentrant hold (#1244).
     */
    @Test
    void anInversionBehindAReentrantHoldFires() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        onNewThread(() -> {
            validator.recordLockAcquisition(a);
            validator.recordLockAcquisition(a);
            validator.recordLockRelease(a); // a is still held once
            validator.recordLockAcquisition(b);
            validator.recordLockRelease(b);
            validator.recordLockRelease(a);
        });
        onNewThread(() -> nest(validator, b, a));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertEquals(1, report.inconsistentOrderings.size(), report.toString());
    }

    /** A lock released before the next is taken imposes no order on it. */
    @Test
    void aReleasedLockRecordsNoEdge() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        onNewThread(() -> {
            validator.recordLockAcquisition(a);
            validator.recordLockRelease(a);
            validator.recordLockAcquisition(b);
            validator.recordLockRelease(b);
        });
        onNewThread(() -> nest(validator, b, a));

        assertFalse(validator.validateLockOrder().hasIssues());
    }

    /**
     * Re-entering a lock the thread already holds cannot block, so it is not nested inside the
     * locks taken since: {@code a, b, a} next to another thread's {@code a, b} is one order.
     */
    @Test
    void reenteringAHeldLockRecordsNoEdgeIntoIt() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        onNewThread(() -> nest(validator, a, b, a));
        onNewThread(() -> nest(validator, a, b));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues(), "re-entering a cannot wait for it: " + report);
    }

    /** Three threads closing a cycle through three locks is the dining-philosophers deadlock. */
    @Test
    void aThreeThreadCycleFires() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        onNewThread(() -> nest(validator, a, b));
        onNewThread(() -> nest(validator, b, c));
        onNewThread(() -> nest(validator, c, a));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertTrue(report.inconsistentOrderings.isEmpty(), "no pair is inverted: " + report);
        assertEquals(3, report.potentialDeadlockCycles.size(), report.toString());
    }

    /** The same three edges from one thread, or under one gate, cannot deadlock. */
    @Test
    void aThreeLockCycleFromOneThreadOrUnderOneGateIsSilent() throws InterruptedException {
        LockOrderValidator oneThread = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        onNewThread(() -> {
            nest(oneThread, a, b);
            nest(oneThread, b, c);
            nest(oneThread, c, a);
        });
        assertFalse(oneThread.validateLockOrder().hasIssues());

        LockOrderValidator gated = new LockOrderValidator();
        Object gate = new Object();
        onNewThread(() -> nest(gated, gate, a, b));
        onNewThread(() -> nest(gated, gate, b, c));
        onNewThread(() -> nest(gated, gate, c, a));
        LockOrderValidator.LockOrderReport report = gated.validateLockOrder();
        assertFalse(report.hasIssues(), report.toString());
    }

    /**
     * A synchronized method's exit is not woven on an exception, so its hold can go stale. A hold
     * the thread no longer has must not nest what it takes next (#1243).
     */
    @Test
    void aStaleMethodMonitorHoldRecordsNoEdge() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object monitor = new Object();
        Object b = new Object();
        onNewThread(() -> {
            validator.recordMethodMonitorAcquisition(monitor); // never released, never really held
            nest(validator, b);
        });
        onNewThread(() -> nest(validator, b, monitor));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertFalse(report.hasIssues(), "the monitor was not held when b was taken: " + report);
    }

    /** A method monitor the thread really holds nests what it takes, like any other lock. */
    @Test
    void aHeldMethodMonitorNestsWhatItTakes() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object monitor = new Object();
        Object b = new Object();
        onNewThread(() -> {
            synchronized (monitor) {
                validator.recordMethodMonitorAcquisition(monitor);
                nest(validator, b);
                validator.recordLockRelease(monitor);
            }
        });
        onNewThread(() -> nest(validator, b, monitor));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertEquals(1, report.inconsistentOrderings.size(), report.toString());
    }

    /**
     * {@code Object.wait} lets every hold of the monitor go and takes them all back: after the
     * matching exits the monitor is free and nests nothing.
     */
    @Test
    void aWaitHandsBackEveryHoldAndTakesThemAgain() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object monitor = new Object();
        Object b = new Object();
        int[] holds = new int[1];
        onNewThread(() -> {
            validator.recordLockAcquisition(monitor);
            validator.recordLockAcquisition(monitor);
            holds[0] = validator.recordReleaseForWait(monitor);
            validator.recordReacquireAfterWait(monitor, holds[0]);
            validator.recordLockRelease(monitor);
            validator.recordLockRelease(monitor);
            nest(validator, b);
        });
        onNewThread(() -> nest(validator, b, monitor));

        assertEquals(2, holds[0]);
        assertFalse(validator.validateLockOrder().hasIssues());
    }

    /** The re-acquisition after a wait blocks, so it is nested inside whatever else is held. */
    @Test
    void theReacquisitionAfterAWaitIsNestedInsideOtherHolds() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object outer = new Object();
        Object monitor = new Object();
        onNewThread(() -> {
            validator.recordLockAcquisition(monitor);
            int holds = validator.recordReleaseForWait(monitor);
            validator.recordLockRelease(monitor); // not held while waiting: a no-op
            validator.recordLockAcquisition(outer);
            validator.recordReacquireAfterWait(monitor, holds);
            validator.recordLockRelease(monitor);
            validator.recordLockRelease(outer);
        });
        onNewThread(() -> nest(validator, monitor, outer));

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertEquals(1, report.inconsistentOrderings.size(), report.toString());
    }

    /** A condition's lock is found among the recorded holds, past a newer one it does not belong to (#1256). */
    @Test
    void aConditionsLockIsFoundAmongTheHolds() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        ReentrantLock outer = new ReentrantLock();
        ReentrantLock inner = new ReentrantLock();
        ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock();
        Condition outerReady = outer.newCondition();
        Condition writeReady = readWrite.writeLock().newCondition();
        Condition unheld = new ReentrantLock().newCondition();
        Object[] found = new Object[3];
        onNewThread(() -> {
            outer.lock();
            inner.lock();
            readWrite.writeLock().lock();
            try {
                validator.recordLockAcquisition(outer);
                validator.recordLockAcquisition(inner);
                validator.recordLockAcquisition(readWrite); // the validator's identity for a view
                found[0] = validator.heldLockOf(outerReady);
                found[1] = validator.heldLockOf(writeReady);
                found[2] = validator.heldLockOf(unheld);
            } finally {
                readWrite.writeLock().unlock();
                inner.unlock();
                outer.unlock();
            }
        });

        assertSame(outer, found[0], "asked past inner and the read-write lock, which throw for it");
        assertSame(readWrite, found[1], "a write lock's condition belongs to its owner");
        assertNull(found[2], "a lock the thread does not hold is not its condition's lock here");
    }
}
