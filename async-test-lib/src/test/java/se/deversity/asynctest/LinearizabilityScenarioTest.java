package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.WorkerSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operations declared once and drawn per worker (#935): the test author names what a worker may do,
 * not the order it does it in, and the replay seed makes a drawn scenario repeatable.
 */
class LinearizabilityScenarioTest {

    /** A counter: increment returns the count after it, get returns the count. */
    private static final SequentialSpec<int[]> COUNTER = SequentialSpec.of(
            () -> new int[1], int[]::clone,
            (state, op, arg) -> op.equals("increment") ? ++state[0] : state[0]);

    private static AsyncTestConfig rounds(int threads, int invocations, long seed) {
        return AsyncTestConfig.builder().threads(threads).invocations(invocations)
                .replaySeed(seed).timeoutMs(60_000).licenseMockMode(true).build();
    }

    /** Runs a drawn scenario and returns what each (round, worker) drew, in order. */
    private static Map<String, List<String>> drawn(long seed) throws Throwable {
        Map<String, List<String>> drawn = new TreeMap<>();
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new);
        ConcurrentLinkedQueue<String[]> seen = new ConcurrentLinkedQueue<>();
        history.operation("increment", random -> null, (counter, arg) -> {
                    seen.add(new String[] {where(), "increment"});
                    return counter.incrementAndGet();
                })
                .operation("add", random -> random.nextInt(100), (counter, arg) -> {
                    seen.add(new String[] {where(), "add(" + arg + ")"});
                    return counter.addAndGet((Integer) arg);
                });
        AsyncTestRunner.run(rounds(3, 4, seed), () -> history.generate(6));
        for (String[] s : seen) {
            drawn.computeIfAbsent(s[0], k -> new ArrayList<>()).add(s[1]);
        }
        return drawn;
    }

    private static String where() {
        AsyncTestContext.Round round = AsyncTestContext.currentRound();
        return (round == null ? 0 : round.number()) + "/" + WorkerSlot.current();
    }

    @Test
    void theSameSeedDrawsTheSameScenario_andAnotherSeedAnotherOne() throws Throwable {
        Map<String, List<String>> first = drawn(42L);
        assertEquals(12, first.size(), "4 rounds of 3 workers, each with its own draw: " + first.keySet());
        first.values().forEach(ops -> assertEquals(6, ops.size(), ops.toString()));
        assertTrue(new java.util.HashSet<>(first.values()).size() > 6,
                "workers and rounds must draw their own scenarios, not share one: " + first);
        assertEquals(first, drawn(42L), "a replay seed must replay the scenario");
        assertNotEquals(first, drawn(43L), "a different seed should draw a different scenario");
    }

    @Test
    void aDrawnScenarioOnAnAtomicCounterIsLinearizable() throws Throwable {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new);
        history.operation("increment", random -> null, (counter, arg) -> counter.incrementAndGet())
                .operation("get", random -> null, (counter, arg) -> counter.get());
        AsyncTestRunner.run(rounds(4, 10, 7L), () -> history.generate(8));

        assertEquals(10, history.rounds());
        history.assertLinearizable(COUNTER);
    }

    /** A counter whose increment reads, pauses, then writes back: updates are lost under overlap. */
    static final class SlowReadThenWriteCounter {
        private volatile int value;

        int increment() {
            int read = value;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            value = read + 1;
            return read + 1;
        }

        int get() {
            return value;
        }
    }

    @Test
    void aDrawnScenarioFindsALostUpdateNobodyScripted() throws Throwable {
        OperationHistory<SlowReadThenWriteCounter> history = OperationHistory.of(SlowReadThenWriteCounter::new);
        history.operation("increment", random -> null, (counter, arg) -> counter.increment())
                .operation("get", random -> null, (counter, arg) -> counter.get());
        AsyncTestRunner.run(rounds(4, 10, 7L), () -> history.generate(8));

        AssertionError e = assertThrows(AssertionError.class, () -> history.assertLinearizable(COUNTER));
        assertTrue(e.getMessage().contains("is not linearizable"), e.getMessage());
    }

    @Test
    void callsWithinOneRoundContinueTheWorkersStream_ratherThanRestartingIt() throws Throwable {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new);
        ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        history.operation("add", random -> random.nextInt(1_000_000), (counter, arg) -> {
            seen.add(where() + " " + arg);
            return counter.addAndGet((Integer) arg);
        });
        AsyncTestRunner.run(rounds(1, 1, 5L), () -> {
            for (int i = 0; i < 6; i++) {
                history.generate(1);
            }
        });
        assertEquals(6, new java.util.HashSet<>(seen).size(),
                "six generate(1) calls drew the same argument, so each restarted the stream: " + seen);
    }

    @Test
    void generatingWithNothingDeclaredIsRefused() {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> history.generate(1));
        assertTrue(e.getMessage().contains("operation("), e.getMessage());
    }
}
