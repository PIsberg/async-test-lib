package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OperationHistory} on real runs, in both directions (invariant 10, #924): correct
 * concurrent objects stay linearizable round after round, and a counter whose increment is a read
 * followed by a write fails on the first round.
 */
class LinearizabilityE2eTest {

    /** A counter: every increment returns the count after it. */
    private static final SequentialSpec<int[]> COUNTER =
            SequentialSpec.of(() -> new int[1], int[]::clone, (state, op, arg) -> ++state[0]);

    /** A FIFO queue: offer returns true, poll returns the head or null. */
    private static final SequentialSpec<LinkedList<Object>> QUEUE = SequentialSpec.of(
            LinkedList::new,
            LinkedList::new,
            (state, op, arg) -> op.equals("offer") ? state.offer(arg) : state.poll());

    private static AsyncTestConfig rounds(int threads, int invocations) {
        return AsyncTestConfig.builder().threads(threads).invocations(invocations)
                .timeoutMs(60_000).licenseMockMode(true).build();
    }

    @Test
    void anAtomicCounterIsLinearizable() throws Throwable {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new);
        AsyncTestRunner.run(rounds(3, 30), () -> {
            AtomicInteger counter = history.subject();
            history.call("increment", null, counter::incrementAndGet);
            history.call("increment", null, counter::incrementAndGet);
        });

        assertEquals(30, history.rounds(), "each round keeps its own history");
        history.assertLinearizable(COUNTER);
    }

    @Test
    void aConcurrentLinkedQueueIsLinearizable() throws Throwable {
        OperationHistory<ConcurrentLinkedQueue<Object>> history = OperationHistory.of(ConcurrentLinkedQueue::new);
        AtomicInteger values = new AtomicInteger();
        AsyncTestRunner.run(rounds(3, 30), () -> {
            ConcurrentLinkedQueue<Object> queue = history.subject();
            int value = values.incrementAndGet();
            history.call("offer", value, () -> queue.offer(value));
            history.call("poll", null, queue::poll);
        });

        history.assertLinearizable(QUEUE);
    }

    /** Reads, waits for every worker of the round to have read, then writes: a lost update. */
    static final class ReadThenWriteCounter {
        private int value;

        int increment() {
            int read = value;
            AsyncTestContext.rendezvous();
            value = read + 1;
            return read + 1;
        }
    }

    @Test
    void aReadThenWriteCounterIsNot() throws Throwable {
        OperationHistory<ReadThenWriteCounter> history = OperationHistory.of(ReadThenWriteCounter::new);
        AsyncTestRunner.run(rounds(2, 3), () -> {
            ReadThenWriteCounter counter = history.subject();
            history.call("increment", null, counter::increment);
        });

        AssertionError e = assertThrows(AssertionError.class, () -> history.assertLinearizable(COUNTER));
        assertTrue(e.getMessage().startsWith("Round 1 is not linearizable"), e.getMessage());
        assertTrue(e.getMessage().contains("increment() -> 1"), e.getMessage());
    }

    @Test
    void aCheckOverNothingFails() {
        OperationHistory<Object> history = OperationHistory.of(Object::new);
        AssertionError e = assertThrows(AssertionError.class, () -> history.assertLinearizable(COUNTER));
        assertTrue(e.getMessage().contains("No operations were recorded"), e.getMessage());
    }

    @Test
    void aRoundMayRecordAtMostSixtyFourOperations() {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new);
        AtomicInteger counter = history.subject();
        for (int i = 0; i < 64; i++) {
            history.call("increment", null, counter::incrementAndGet);
        }
        assertThrows(IllegalStateException.class, () -> history.call("increment", null, counter::incrementAndGet));
    }
}
