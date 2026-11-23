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

    @Test
    void multipleArgumentDrawsPerScenarioFindKeyCollisionRaceMissedBySingleDraw() {
        SequentialSpec<int[]> bucketSpec = SequentialSpec.of(
                () -> new int[8], int[]::clone,
                (state, op, arg) -> ++state[(Integer) arg]);
        OperationHistory<int[]> history = OperationHistory.of(() -> new int[8])
                .operation("incBucket", random -> random.nextInt(8), (buckets, key) -> {
                    int k = (Integer) key;
                    int read = buckets[k];
                    AsyncTestContext.schedulePoint();
                    buckets[k] = read + 1;
                    return read + 1;
                });
        AssertionError e = assertThrows(AssertionError.class,
                () -> history.assertLinearizableForAllScenarios(bucketSpec, 2, 1, 16));
        assertTrue(e.getMessage().contains("is not linearizable"), e.getMessage());
    }

    // ---- #1493: what used to pass without being checked ------------------------------------------

    @Test
    void aScenarioWhoseRunThrowsFailsNamingTheScenario() {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new)
                .operation("boom", random -> null, (counter, arg) -> {
                    throw new IllegalStateException("the subject crashed");
                });
        AssertionError e = assertThrows(AssertionError.class,
                () -> history.assertLinearizableForAllScenarios(COUNTER, 2, 1));
        assertTrue(e.getMessage().contains("failed to run") && e.getMessage().contains(": boom()"),
                "the scenario is named: " + e.getMessage());
        assertTrue(causes(e).contains("the subject crashed"),
                "a crash is the scenario's failure, not a pass: " + causes(e));
    }

    @Test
    void aSingleWorkerThatDisagreesWithTheSpecFails() {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new)
                .operation("increment", random -> null, (counter, arg) -> counter.addAndGet(2));
        AssertionError e = assertThrows(AssertionError.class,
                () -> history.assertLinearizableForAllScenarios(COUNTER, 1, 1),
                "one worker alone is sequential, but its results must still match the spec");
        assertTrue(e.getMessage().contains("is not linearizable"), e.getMessage());
    }

    @Test
    void aScenarioTheSearchCannotCheckFailsRatherThanPasses() {
        // 65 operations on one worker: more than one search takes, the same AssertionError path an
        // undecided search takes, reached here without a million-state search.
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new)
                .operation("increment", random -> null, (counter, arg) -> counter.incrementAndGet());
        AssertionError e = assertThrows(AssertionError.class,
                () -> history.assertLinearizableForAllScenarios(COUNTER, 1, 65));
        assertTrue(causes(e).contains("one search takes at most"), causes(e));
    }

    /** {@return the messages of {@code e} and every cause under it, one per line} */
    private static String causes(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t).append('\n');
        }
        return sb.toString();
    }
}
