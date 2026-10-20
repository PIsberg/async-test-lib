package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small scenarios enumerated instead of drawn (#1082): every sequence of up to k declared
 * operations per worker, smallest first, each run under a controlled schedule and several seeds.
 * A race only a particular small scenario exposes is found whatever a random draw would have
 * picked, and a pass covers the whole bounded space, which the message says.
 */
class LinearizabilityEnumerationTest {

    private static final SequentialSpec<int[]> COUNTER = SequentialSpec.of(
            () -> new int[1], int[]::clone,
            (state, op, arg) -> op.equals("increment") ? ++state[0] : state[0]);

    /** An increment that reads, reaches a scheduling point, then writes: an update lost to a switch. */
    static final class ReadPointWriteCounter {
        private int value;

        int increment() {
            int read = value;
            AsyncTestContext.schedulePoint();
            value = read + 1;
            return read + 1;
        }

        int get() {
            return value;
        }
    }

    @Test
    void theSmallestFailingScenarioIsFound() {
        OperationHistory<ReadPointWriteCounter> history = OperationHistory.of(ReadPointWriteCounter::new)
                .operation("get", random -> null, (counter, arg) -> counter.get())
                .operation("increment", random -> null, (counter, arg) -> counter.increment());
        AssertionError e = assertThrows(AssertionError.class,
                () -> history.assertLinearizableForAllScenarios(COUNTER, 2, 2));
        String message = e.getMessage();
        assertTrue(message.contains("is not linearizable"), message);
        assertTrue(message.matches("(?s).*worker 0: increment\\(\\)\\R\\s*worker 1: increment\\(\\).*"),
                "smallest first, so the scenario found is one increment on each worker: " + message);
        assertFalse(message.contains("get()"), "a get is never needed for this race: " + message);
    }

    @Test
    void aLinearizableObjectPassesTheWholeBoundedSpace() {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new)
                .operation("get", random -> null, (counter, arg) -> counter.get())
                .operation("increment", random -> null, (counter, arg) -> counter.incrementAndGet());
        String covered = history.assertLinearizableForAllScenarios(COUNTER, 2, 2);
        assertTrue(covered.contains("49 scenarios"), "(1 + 2 + 4)^2 scenarios of up to 2 operations on 2"
                + " workers: " + covered);
    }

    @Test
    void aSpaceLargerThanTheBudgetIsRefusedRatherThanPartlySearched() {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new)
                .operation("get", random -> null, (counter, arg) -> counter.get())
                .operation("increment", random -> null, (counter, arg) -> counter.incrementAndGet());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> history.assertLinearizableForAllScenarios(COUNTER, 4, 6));
        assertTrue(e.getMessage().contains("scenarios"), e.getMessage());
    }
}
