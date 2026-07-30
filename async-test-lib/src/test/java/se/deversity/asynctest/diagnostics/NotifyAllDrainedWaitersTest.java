package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical lost-wakeup bug: several threads wait on a monitor, and the producer calls
 * {@code notify()} instead of {@code notifyAll()}, so all but one waiter can be left asleep.
 *
 * <p>{@code analyze()} tested {@code state.waitingThreads.get() > 1} — the <em>live</em> waiter
 * count, which {@code recordWaiterReleased} decrements. But {@code analyze()} runs after the
 * test has finished, when every thread has returned from {@code wait()} and the counter has
 * drained back to zero. So {@code > 1} was false and the bug went unreported.
 *
 * <p>It fired only if threads were still parked in {@code wait()} at analysis time — i.e. when
 * the run had hung anyway. The existing test passed because it recorded two waiters and never
 * released them, holding the counter artificially high: synthetic events that hid the defect.
 *
 * <p>Detection must rest on what was true when the notify happened, not on what is left over
 * afterwards.
 */
class NotifyAllDrainedWaitersTest {

    @Test
    void notifyWithSeveralWaitersIsReportedEvenAfterTheWaitersHaveWokenUp() {
        NotifyAllValidator validator = new NotifyAllValidator();
        Object monitor = new Object();

        // Three threads park on the monitor.
        validator.recordWaiterAdded(monitor, "queue");
        validator.recordWaiterAdded(monitor, "queue");
        validator.recordWaiterAdded(monitor, "queue");

        // The producer signals with notify() — the bug.
        validator.recordNotify(monitor, false);

        // The waiters eventually wake and leave, draining the live counter to zero,
        // exactly as they do in a real run before analyze() is ever called.
        validator.recordWaiterReleased(monitor);
        validator.recordWaiterReleased(monitor);
        validator.recordWaiterReleased(monitor);

        NotifyAllValidator.NotifyAllReport report = validator.analyze();

        assertFalse(report.notifyInsteadOfNotifyAll.isEmpty(),
            "notify() with multiple waiters must be reported after the waiters have drained");
        assertTrue(report.hasIssues(), "the report must claim issues");
    }

    /** notifyAll() with many waiters is correct — it must stay clean. */
    @Test
    void notifyAllWithManyWaitersIsNotAFinding() {
        NotifyAllValidator validator = new NotifyAllValidator();
        Object monitor = new Object();

        validator.recordWaiterAdded(monitor, "queue");
        validator.recordWaiterAdded(monitor, "queue");
        validator.recordNotify(monitor, true);
        validator.recordWaiterReleased(monitor);
        validator.recordWaiterReleased(monitor);

        assertTrue(validator.analyze().notifyInsteadOfNotifyAll.isEmpty(),
            "notifyAll() is the correct call and must not be flagged");
    }

    /** notify() with a single waiter is legitimate — it must stay clean. */
    @Test
    void notifyWithOneWaiterIsNotAFinding() {
        NotifyAllValidator validator = new NotifyAllValidator();
        Object monitor = new Object();

        validator.recordWaiterAdded(monitor, "queue");
        validator.recordNotify(monitor, false);
        validator.recordWaiterReleased(monitor);

        assertTrue(validator.analyze().notifyInsteadOfNotifyAll.isEmpty(),
            "notify() with a single waiter wakes that waiter — not a lost wakeup");
    }
}
