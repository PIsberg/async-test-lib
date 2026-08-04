package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lower bounds {@link AsyncTestConfig.Builder#build()} enforces on the execution
 * shape.
 *
 * <p><strong>Why this exists.</strong> {@code AsyncTestInvocationInterceptor} calls
 * {@code invocation.skip()} before handing control to {@code ConcurrencyRunner}, so JUnit
 * counts the test as executed no matter what the runner does afterwards. With
 * {@code invocations = 0} the runner's round loop never entered, no detector ever received
 * data, and the test reported green having run the body zero times — the silent-zero-execution
 * failure a stress-testing library exists to prevent. {@code threads = 0} happened to fail
 * loudly, but only as a side effect of {@code new CyclicBarrier(0)} throwing deep inside the
 * round, with a message that named the barrier rather than the configuration mistake. Both
 * bounds now fail at {@code build()} time with the offending parameter in the message, before
 * any thread is created.
 */
class AsyncTestConfigValidationTest {

    @Test
    void zeroInvocationsIsRejectedAtBuildTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncTestConfig.builder().invocations(0).build(),
                "invocations = 0 must not build: the runner's loop would run the test body "
                        + "zero times while JUnit reports the test as passed");
        assertTrue(e.getMessage().contains("invocations"),
                "The message must name the offending parameter so the user can find the "
                        + "annotation attribute to fix. Was: " + e.getMessage());
    }

    @Test
    void negativeInvocationsIsRejectedAtBuildTime() {
        assertThrows(IllegalArgumentException.class,
                () -> AsyncTestConfig.builder().invocations(-1).build());
    }

    @Test
    void zeroThreadsIsRejectedAtBuildTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncTestConfig.builder().threads(0).build(),
                "threads = 0 must fail at build() with a message naming the parameter, not "
                        + "later inside the round as new CyclicBarrier(0)");
        assertTrue(e.getMessage().contains("threads"),
                "The message must name the offending parameter. Was: " + e.getMessage());
    }

    @Test
    void negativeThreadsIsRejectedAtBuildTime() {
        assertThrows(IllegalArgumentException.class,
                () -> AsyncTestConfig.builder().threads(-8).build());
    }

    @Test
    void minimumViableShapeStillBuilds() {
        assertDoesNotThrow(() -> AsyncTestConfig.builder().threads(1).invocations(1).build(),
                "1 thread x 1 invocation is a legitimate (if weak) configuration and must "
                        + "keep building — the bound is on zero and below only");
    }
}
