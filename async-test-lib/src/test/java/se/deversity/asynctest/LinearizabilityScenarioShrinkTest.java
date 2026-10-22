package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failing drawn scenario is shrunk by re-running it (#1025): {@code assertLinearizable} replays
 * the round's drawn operations under its seed and a controlled schedule with operations removed,
 * delta debugging style, and reports the smallest scenario that still fails beside the prefix.
 *
 * <p>The subject loses an update only when a switch lands between an increment's read and its
 * write, which the scheduling point inside it offers; the {@code get}s around the increments are
 * noise the shrink must take out. The smallest scenario that still fails is two increments on two
 * workers that both return 1.
 */
class LinearizabilityScenarioShrinkTest {

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

    private static OperationHistory<ReadPointWriteCounter> history() {
        return OperationHistory.of(ReadPointWriteCounter::new)
                .operation("increment", random -> null, (counter, arg) -> counter.increment())
                .operation("get", random -> null, (counter, arg) -> counter.get());
    }

    private static AsyncTestConfig controlled(long seed) {
        return AsyncTestConfig.builder().threads(3).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true).build();
    }

    /** {@return the failure of the first seed in 1..64 whose drawn scenario loses an update} */
    private static AssertionError firstFailure() throws Throwable {
        for (long seed = 1; seed <= 64; seed++) {
            OperationHistory<ReadPointWriteCounter> history = history();
            AsyncTestRunner.run(controlled(seed), () -> history.generate(6));
            try {
                history.assertLinearizable(COUNTER);
            } catch (AssertionError e) {
                return e;
            }
        }
        throw new AssertionError("no seed in 1..64 lost an update, so there is nothing to shrink");
    }

    @Test
    void aFailingDrawnScenarioIsShrunkToTwoIncrements() throws Throwable {
        String message = firstFailure().getMessage();
        assertTrue(message.contains("is not linearizable"), message);
        Matcher shrunk = Pattern.compile("(\\d+) of the (\\d+) drawn operations still fail").matcher(message);
        assertTrue(shrunk.find(), "the failure must carry the shrunk scenario: " + message);
        assertEquals(2, Integer.parseInt(shrunk.group(1)), "a lost update needs two increments: " + message);
        assertEquals(18, Integer.parseInt(shrunk.group(2)), "three workers drew six each: " + message);
        String scenario = message.substring(shrunk.end());
        assertFalse(scenario.contains("get()"), "every get is noise the shrink removes: " + message);
        assertEquals(2, scenario.split("increment\\(\\)", -1).length - 1, message);
        assertTrue(scenario.matches("(?s).*worker (\\d+): increment\\(\\).*worker (?!\\1)\\d+: increment\\(\\).*"),
                "the two increments must be on two workers, or nothing could interleave: " + message);
    }

    @Test
    void aScenarioThatIsNotDrawnIsNotShrunk() throws Throwable {
        OperationHistory<ReadPointWriteCounter> history = OperationHistory.of(ReadPointWriteCounter::new);
        AssertionError found = null;
        for (long seed = 1; seed <= 64 && found == null; seed++) {
            OperationHistory<ReadPointWriteCounter> each = OperationHistory.of(ReadPointWriteCounter::new);
            AsyncTestRunner.run(controlled(seed), () -> {
                ReadPointWriteCounter counter = each.subject();
                each.call("increment", null, counter::increment);
            });
            try {
                each.assertLinearizable(COUNTER);
            } catch (AssertionError e) {
                found = e;
            }
        }
        assertTrue(found != null, "no seed lost an update with three hand-written increments");
        assertFalse(found.getMessage().contains("drawn operations still fail"),
                "operations recorded with call() were not drawn, so there is no scenario to replay: "
                        + found.getMessage());
        assertEquals(0, history.rounds());
    }

    @Test
    void verifiedAgainstAlsoShrinksAFailingDrawnScenario() throws Throwable {
        String message = null;
        for (long seed = 1; seed <= 64 && message == null; seed++) {
            OperationHistory<ReadPointWriteCounter> history = history().verifiedAgainst(COUNTER);
            AsyncFindings findings = AsyncTestRunner.run(
                    AsyncTestConfig.builder().threads(3).invocations(1).replaySeed(seed)
                            .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true)
                            .failOn(FailOn.NONE).build(),
                    () -> history.generate(6));
            var violations = findings.violationsFrom("Linearizability");
            if (!violations.isEmpty()) {
                message = String.valueOf(violations.get(0).attributes().get("report"));
            }
        }
        assertTrue(message != null, "no seed in 1..64 lost an update under verifiedAgainst");
        Matcher shrunk = Pattern.compile("(\\d+) of the (\\d+) drawn operations still fail").matcher(message);
        assertTrue(shrunk.find(), "verifiedAgainst finding must carry the shrunk scenario: " + message);
        assertEquals(2, Integer.parseInt(shrunk.group(1)), message);
    }

    @Test
    void aLinearizableScenarioIsNotReRun() throws Throwable {
        OperationHistory<java.util.concurrent.atomic.AtomicInteger> history =
                OperationHistory.of(java.util.concurrent.atomic.AtomicInteger::new)
                        .operation("increment", random -> null, (counter, arg) -> counter.incrementAndGet())
                        .operation("get", random -> null, (counter, arg) -> counter.get());
        AsyncTestRunner.run(controlled(3L), () -> history.generate(6));
        history.assertLinearizable(COUNTER);
        assertEquals(1, history.rounds(), "a passing check re-runs nothing into the history");
        assertThrows(NullPointerException.class, () -> history.assertLinearizable(null));
    }
}
