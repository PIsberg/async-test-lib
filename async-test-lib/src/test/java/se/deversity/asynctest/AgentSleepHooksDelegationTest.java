package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sleep hook must actually sleep.
 *
 * <p>Everything else about these hooks is asserted through the detector: which overload records,
 * what it records when the millisecond part is zero, and that a sleep outside a lock stays
 * silent. None of that changes if the hook records and then never calls {@code Thread.sleep},
 * which is why PIT reported "removed call to Thread::sleep" surviving in all six entry points
 * (#476). The hook is substituted for the caller's own sleep, so dropping it would make woven
 * code run through a pause that unwoven code takes - the one thing a weave may never do.
 *
 * <p>The assertion is a lower bound only. {@code Thread.sleep} guarantees at least the requested
 * time and says nothing about the upper one, so a bound from below cannot be made flaky by a
 * loaded machine; an upper bound would be, which is why there is none here.
 */
class AgentSleepHooksDelegationTest {

    /** Long enough that a dropped delegation cannot be mistaken for a coarse clock. */
    private static final long SLEEP_MILLIS = 50L;

    /** The floor asserted against, below the requested time only to absorb clock granularity. */
    private static final long FLOOR_MILLIS = 30L;

    private static final Object MONITOR = new Object();

    /** Runs {@code sleeper} and {@return the milliseconds it took}. */
    private static long elapsedMillisOf(Sleeper sleeper) throws InterruptedException {
        long start = System.nanoTime();
        sleeper.sleep();
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static void assertSlept(String what, Sleeper sleeper) throws InterruptedException {
        long elapsed = elapsedMillisOf(sleeper);
        assertTrue(elapsed >= FLOOR_MILLIS,
                what + " must perform the sleep it replaced; asked for " + SLEEP_MILLIS
                        + "ms and returned after " + elapsed + "ms");
    }

    @Test
    @DisplayName("every sleep hook sleeps for at least the time it was asked for")
    void everySleepHookSleeps() throws InterruptedException {
        assertSlept("sleep(long)", () -> AgentSleepHooks.sleep(SLEEP_MILLIS));
        assertSlept("sleep(Duration)",
                () -> AgentSleepHooks.sleep(Duration.ofMillis(SLEEP_MILLIS)));
        assertSlept("sleep(long, int)", () -> AgentSleepHooks.sleep(SLEEP_MILLIS, 0));

        assertSlept("sleepHoldingMonitor(long, Object)",
                () -> AgentSleepHooks.sleepHoldingMonitor(SLEEP_MILLIS, MONITOR));
        assertSlept("sleepHoldingMonitor(Duration, Object)",
                () -> AgentSleepHooks.sleepHoldingMonitor(Duration.ofMillis(SLEEP_MILLIS), MONITOR));
        assertSlept("sleepHoldingMonitor(long, int, Object)",
                () -> AgentSleepHooks.sleepHoldingMonitor(SLEEP_MILLIS, 0, MONITOR));
    }

    /** One of the six calls, with the interrupt the hooks propagate. */
    @FunctionalInterface
    private interface Sleeper {
        void sleep() throws InterruptedException;
    }
}
