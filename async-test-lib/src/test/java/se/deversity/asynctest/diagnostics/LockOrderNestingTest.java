package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lock-order edge only exists when one lock is acquired <em>while another is held</em>. The
 * validator instead read edges off adjacent entries of {@code lockOrder} — an append-only list
 * that {@code recordLockRelease} never trims ("we keep the full order for analysis").
 *
 * <p>So a thread that correctly takes A then B, releases both, and does it again — which is
 * exactly what happens when the runner replays a test body on a pooled thread, 100 times by
 * default — produced {@code [A, B, A, B]}. Its adjacent pairs are {@code A->B}, <b>{@code B->A}</b>,
 * {@code A->B}: a phantom reverse edge, an "inconsistent ordering" report, and an A→B→A deadlock
 * cycle — all from code that is perfectly correct and never deadlocks.
 *
 * <p>The currently-held set ({@code acquiredLocks}), which is the only sound basis for a nesting
 * edge, was maintained but never read by any analysis.
 */
class LockOrderNestingTest {

    @Test
    void consistentlyOrderedNestedLockingIsNotADeadlockCycle() {
        LockOrderValidator validator = new LockOrderValidator();
        Object lockA = new Object();
        Object lockB = new Object();

        // The same thread runs the same correct body three times, as the runner replays it.
        for (int invocation = 0; invocation < 3; invocation++) {
            validator.recordLockAcquisition(lockA);
            validator.recordLockAcquisition(lockB);   // B nested inside A — always this order
            validator.recordLockRelease(lockB);
            validator.recordLockRelease(lockA);
        }

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();

        assertTrue(report.inconsistentOrderings.isEmpty(),
            "one consistent order (A then B) must never be reported as inconsistent: "
                + report.inconsistentOrderings);
        assertTrue(report.potentialDeadlockCycles.isEmpty(),
            "repeating a correct nesting must not manufacture a deadlock cycle: "
                + report.potentialDeadlockCycles);
    }

    /** Two locks taken one after the other, never nested, cannot deadlock — no edge exists. */
    @Test
    void sequentialNonOverlappingAcquisitionsAreNotAnOrdering() {
        LockOrderValidator validator = new LockOrderValidator();
        Object lockA = new Object();
        Object lockB = new Object();

        validator.recordLockAcquisition(lockA);
        validator.recordLockRelease(lockA);          // A is released before B is taken
        validator.recordLockAcquisition(lockB);
        validator.recordLockRelease(lockB);

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();

        assertTrue(report.potentialDeadlockCycles.isEmpty(),
            "locks that are never held at the same time impose no ordering: "
                + report.potentialDeadlockCycles);
    }

    /** The real bug must still be caught: two threads nesting the same pair in opposite orders. */
    @Test
    void oppositeNestingOrdersAcrossThreadsIsStillReported() throws InterruptedException {
        LockOrderValidator validator = new LockOrderValidator();
        Object lockA = new Object();
        Object lockB = new Object();

        CountDownLatch done = new CountDownLatch(2);

        Thread ab = new Thread(() -> {
            validator.recordLockAcquisition(lockA);
            validator.recordLockAcquisition(lockB);   // A then B
            validator.recordLockRelease(lockB);
            validator.recordLockRelease(lockA);
            done.countDown();
        });
        Thread ba = new Thread(() -> {
            validator.recordLockAcquisition(lockB);
            validator.recordLockAcquisition(lockA);   // B then A — the classic deadlock setup
            validator.recordLockRelease(lockA);
            validator.recordLockRelease(lockB);
            done.countDown();
        });

        ab.start();
        ab.join();
        ba.start();
        ba.join();
        assertTrue(done.await(5, TimeUnit.SECONDS), "both threads must finish");

        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();

        assertFalse(report.inconsistentOrderings.isEmpty(),
            "the same lock pair nested in opposite orders by two threads must be reported");
        assertTrue(report.hasIssues(), "the report must claim issues");
    }
}
