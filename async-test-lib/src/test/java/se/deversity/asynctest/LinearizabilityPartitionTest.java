package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checking a history one partition at a time (#933): each key of a map is its own object, so a round
 * may record far more than the search's 64 operations as long as no one key gets more than 64.
 *
 * <p>Linearizability is local (Herlihy and Wing): a history of independent objects is linearizable
 * exactly when each object's own history is. So a per-key check proves as much as a whole-history
 * check would, and each search stays small. Both directions, invariant 10: a map whose increment is
 * atomic passes with 120 operations per round, and one whose increment reads then writes fails,
 * naming the key.
 */
class LinearizabilityPartitionTest {

    /** One counter per key: "increment" returns the key's count after it. */
    private static final SequentialSpec<int[]> COUNTER =
            SequentialSpec.of(() -> new int[1], int[]::clone, (state, op, arg) -> ++state[0]);

    /** The key an operation touches is its argument. */
    private static final OperationHistory.Partition BY_KEY = (operation, argument) -> argument;

    private static final int KEYS = 4;
    private static final int PER_WORKER = 30;

    private static AsyncTestConfig rounds(int threads, int invocations) {
        return AsyncTestConfig.builder().threads(threads).invocations(invocations)
                .timeoutMs(60_000).licenseMockMode(true).build();
    }

    @Test
    void anAtomicPerKeyCounterIsLinearizable_wellPastSixtyFourOperationsARound() throws Throwable {
        OperationHistory<ConcurrentHashMap<Integer, Integer>> history = OperationHistory.of(ConcurrentHashMap::new);
        AsyncTestRunner.run(rounds(4, 5), () -> {
            ConcurrentHashMap<Integer, Integer> map = history.subject();
            for (int i = 0; i < PER_WORKER; i++) {
                int key = i % KEYS;
                history.call("increment", key, () -> map.merge(key, 1, Integer::sum));
            }
        });

        history.assertLinearizable(COUNTER, BY_KEY);
    }

    @Test
    void aWholeHistoryCheckRefusesMoreThanSixtyFourOperations_andSaysToPartition() {
        OperationHistory<Map<Integer, Integer>> history = OperationHistory.of(HashMap::new);
        Map<Integer, Integer> map = history.subject();
        for (int i = 0; i < 65; i++) {
            int key = i % KEYS;
            history.call("increment", key, () -> map.merge(key, 1, Integer::sum));
        }

        AssertionError e = assertThrows(AssertionError.class, () -> history.assertLinearizable(COUNTER));
        assertTrue(e.getMessage().contains("65 operations"), e.getMessage());
        assertTrue(e.getMessage().contains("Partition"), e.getMessage());
        history.assertLinearizable(COUNTER, BY_KEY);
    }

    /** Per-key counters whose increment reads, waits for the round's other workers, then writes. */
    static final class ReadThenWriteCounters {
        private final Map<Integer, Integer> values = new ConcurrentHashMap<>();

        int increment(int key) {
            int read = values.getOrDefault(key, 0);
            AsyncTestContext.rendezvous();
            values.put(key, read + 1);
            return read + 1;
        }
    }

    @Test
    void aReadThenWriteKeyFails_andTheReportNamesIt() throws Throwable {
        OperationHistory<ReadThenWriteCounters> history = OperationHistory.of(ReadThenWriteCounters::new);
        AsyncTestRunner.run(rounds(2, 2), () -> {
            ReadThenWriteCounters counters = history.subject();
            history.call("increment", 7, () -> counters.increment(7));
        });

        AssertionError e = assertThrows(AssertionError.class, () -> history.assertLinearizable(COUNTER, BY_KEY));
        assertTrue(e.getMessage().startsWith("Round 1, partition 7, is not linearizable"), e.getMessage());
    }

    @Test
    void aPartitionMayStillHoldAtMostSixtyFourOperations() {
        OperationHistory<Map<Integer, Integer>> history = OperationHistory.of(HashMap::new);
        Map<Integer, Integer> map = history.subject();
        for (int i = 0; i < 65; i++) {
            history.call("increment", 1, () -> map.merge(1, 1, Integer::sum));
        }
        AssertionError e = assertThrows(AssertionError.class, () -> history.assertLinearizable(COUNTER, BY_KEY));
        assertTrue(e.getMessage().contains("partition 1"), e.getMessage());
        assertTrue(e.getMessage().contains("65 operations"), e.getMessage());
    }

    @Test
    void aNullPartitionKeyIsRefused() {
        OperationHistory<Map<Integer, Integer>> history = OperationHistory.of(HashMap::new);
        history.call("increment", null, () -> 1);
        assertThrows(NullPointerException.class, () -> history.assertLinearizable(COUNTER, BY_KEY));
    }

    @Test
    void aRoundMayRecordMoreThanSixtyFour_upToTheRecordingCap() {
        OperationHistory<Map<Integer, Integer>> history = OperationHistory.of(HashMap::new);
        for (int i = 0; i < OperationHistory.MAX_OPERATIONS_PER_ROUND; i++) {
            history.call("noop", i, () -> null);
        }
        assertThrows(IllegalStateException.class, () -> history.call("noop", -1, () -> null));
        assertEquals(1, history.rounds());
    }
}
