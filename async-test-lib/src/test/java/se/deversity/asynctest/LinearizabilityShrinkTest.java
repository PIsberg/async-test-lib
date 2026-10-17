package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.LinearizabilityChecker.Op;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.deversity.asynctest.LinearizabilityChecker.NO_RESPONSE;

/**
 * A history with no linearization is reported as its shortest prefix that already has none.
 * Linearizability is prefix-closed: every prefix of a linearizable history is linearizable, so a
 * prefix with no linearization is a counterexample in its own right, not an artefact of cutting.
 */
class LinearizabilityShrinkTest {

    private static final SequentialSpec<int[]> REGISTER = SequentialSpec.of(
            () -> new int[1],
            int[]::clone,
            (state, op, arg) -> {
                if (op.equals("write")) {
                    state[0] = (Integer) arg;
                    return null;
                }
                return state[0];
            });

    private static Op write(int value, long invoke, long response) {
        return new Op("w", "write", value, null, invoke, response);
    }

    private static Op read(int value, long invoke, long response) {
        return new Op("r", "read", null, value, invoke, response);
    }

    private static long cut(Op... ops) {
        return LinearizabilityChecker.shortestViolatingCut(List.of(ops), REGISTER,
                LinearizabilityChecker.DEFAULT_BUDGET);
    }

    @Test
    void theCutIsTheFirstResponseNoOrderExplains() {
        // read 0 returned at 6 after read 1 had returned at 4: nothing explains it from 6 on, and
        // everything before 6 is explainable. The two operations after it do not move the cut.
        assertEquals(6L, cut(write(1, 1, 2), read(1, 3, 4), read(0, 5, 6), read(1, 7, 8), write(2, 9, 10)));
    }

    @Test
    void anOperationStillRunningAtTheCutCountsAsPending() {
        // write(5) is invoked at 5 and returns at 12, after the stale read at 7. Cut at 7 it is
        // pending: it may or may not have taken effect, and the read of 0 is impossible either way.
        List<Op> ops = List.of(write(1, 1, 2), read(1, 3, 4), write(5, 5, 12), read(0, 6, 7));
        long cut = LinearizabilityChecker.shortestViolatingCut(ops, REGISTER, LinearizabilityChecker.DEFAULT_BUDGET);
        assertEquals(7L, cut);
        List<Op> prefix = LinearizabilityChecker.prefix(ops, cut);
        assertEquals(4, prefix.size());
        assertEquals(NO_RESPONSE, prefix.get(2).response(), "write(5) had not returned at the cut");
        assertEquals(7L, prefix.get(3).response());
    }

    @Test
    void anOperationInvokedAfterTheCutIsLeftOut() {
        List<Op> prefix = LinearizabilityChecker.prefix(
                List.of(write(1, 1, 2), read(0, 3, 4), read(1, 5, 6)), 4L);
        assertEquals(2, prefix.size(), prefix.toString());
    }

    @Test
    void aLinearizableHistoryHasNoCut() {
        assertEquals(-1L, cut(write(1, 1, 2), read(1, 3, 4), write(2, 5, 6), read(2, 7, 8)));
    }

    /** Counts up, except that the third increment returns the count it found, not the new one. */
    static final class ThirdIncrementLies {
        private int value;
        private int calls;

        int increment() {
            calls++;
            value++;
            return calls == 3 ? value - 1 : value;
        }
    }

    @Test
    void theReportListsOnlyTheShortestPrefixAndSaysWhereItEnds() throws Throwable {
        SequentialSpec<int[]> counter = SequentialSpec.of(() -> new int[1], int[]::clone,
                (state, op, arg) -> ++state[0]);
        OperationHistory<ThirdIncrementLies> history = OperationHistory.of(ThirdIncrementLies::new);
        AsyncTestRunner.run(AsyncTestConfig.builder().threads(1).invocations(1).timeoutMs(10_000)
                .licenseMockMode(true).build(), () -> {
            ThirdIncrementLies subject = history.subject();
            for (int i = 0; i < 6; i++) {
                history.call("increment", null, subject::increment);
            }
        });

        AssertionError e = assertThrows(AssertionError.class, () -> history.assertLinearizable(counter));
        String message = e.getMessage();
        assertTrue(message.startsWith("Round 1 is not linearizable"), message);
        assertTrue(message.contains("3 of its 6 operations"), message);
        assertTrue(message.contains("increment() returned 2"), "names the response that broke it: " + message);
        long listed = message.lines().filter(line -> line.startsWith("  [")).count();
        assertEquals(3, listed, "only the prefix is listed: " + message);
        assertFalse(message.contains("-> 5"), "the operations after the cut are left out: " + message);
    }
}
