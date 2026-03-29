package com.github.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link com.github.asynctest.runner.ConcurrencyRunner} helper methods.
 *
 * <p>ConcurrencyRunner's helper methods are private static; we access them via reflection
 * so that we can keep them private (they are not API) while still having direct test coverage.
 */
class ConcurrencyRunnerTest {

    private static final Class<?> RUNNER_CLASS =
            com.github.asynctest.runner.ConcurrencyRunner.class;

    // ---- buildMultiFailureError ----

    @Test
    void buildMultiFailureError_singleAssertionError_returnsOriginal() throws Exception {
        AssertionError original = new AssertionError("only failure");
        AssertionError result = invokeBuildMultiFailure(List.of(original));
        assertSame(original, result,
                "Single AssertionError must be returned unchanged");
    }

    @Test
    void buildMultiFailureError_singleNonAssertion_wrapsInAssertionError() throws Exception {
        RuntimeException cause = new RuntimeException("runtime problem");
        AssertionError result = invokeBuildMultiFailure(List.of(cause));
        assertNotNull(result);
        assertEquals("runtime problem", result.getMessage());
        assertSame(cause, result.getCause());
    }

    @Test
    void buildMultiFailureError_multipleFailures_buildsAggregateMessage() throws Exception {
        AssertionError e1 = new AssertionError("first");
        AssertionError e2 = new AssertionError("second");
        AssertionError result = invokeBuildMultiFailure(List.of(e1, e2));

        assertTrue(result.getMessage().contains("2 concurrent thread(s) failed"),
                "Message must report thread count");
        assertTrue(result.getMessage().contains("first"),  "Message must include first failure");
        assertTrue(result.getMessage().contains("second"), "Message must include second failure");
        assertSame(e1, result.getCause(), "First failure must be attached as cause");
        // Second must be suppressed
        Throwable[] suppressed = result.getSuppressed();
        assertEquals(1, suppressed.length);
        assertSame(e2, suppressed[0]);
    }

    // ---- unwrap ----

    @Test
    void unwrap_invocationTargetException_returnsCause() throws Exception {
        RuntimeException cause = new RuntimeException("inner");
        InvocationTargetException ite = new InvocationTargetException(cause);
        Throwable result = invokeUnwrap(ite);
        assertSame(cause, result);
    }

    @Test
    void unwrap_plainException_returnsSame() throws Exception {
        RuntimeException ex = new RuntimeException("plain");
        Throwable result = invokeUnwrap(ex);
        assertSame(ex, result);
    }

    // ---- remainingMillis ----

    @Test
    void remainingMillis_futureDeadline_returnsPositive() throws Exception {
        long futureNanos = System.nanoTime() + 5_000_000_000L; // 5 seconds from now
        long remaining = invokeRemainingMillis(futureNanos);
        assertTrue(remaining > 0, "Future deadline must yield positive remaining time");
    }

    @Test
    void remainingMillis_pastDeadline_returnsZero() throws Exception {
        long pastNanos = System.nanoTime() - 1_000_000_000L; // 1 second ago
        long remaining = invokeRemainingMillis(pastNanos);
        assertEquals(0L, remaining, "Past deadline must yield 0 (not negative)");
    }

    // ---- isTimeoutLike ----

    @Test
    void isTimeoutLike_messageContainsTimedOut_returnsTrue() throws Exception {
        AssertionError e = new AssertionError("latch timed out after 5000ms");
        assertTrue(invokeIsTimeoutLike(e));
    }

    @Test
    void isTimeoutLike_genericMessage_returnsFalse() throws Exception {
        AssertionError e = new AssertionError("assertion failed");
        assertFalse(invokeIsTimeoutLike(e));
    }

    @Test
    void isTimeoutLike_nullMessage_returnsFalse() throws Exception {
        AssertionError e = new AssertionError((String) null);
        assertFalse(invokeIsTimeoutLike(e));
    }

    // ---- Reflection helpers ----

    private static AssertionError invokeBuildMultiFailure(List<Throwable> failures) throws Exception {
        Method m = RUNNER_CLASS.getDeclaredMethod("buildMultiFailureError", List.class);
        m.setAccessible(true);
        return (AssertionError) m.invoke(null, failures);
    }

    private static Throwable invokeUnwrap(Throwable t) throws Exception {
        Method m = RUNNER_CLASS.getDeclaredMethod("unwrap", Throwable.class);
        m.setAccessible(true);
        return (Throwable) m.invoke(null, t);
    }

    private static long invokeRemainingMillis(long deadlineNanos) throws Exception {
        Method m = RUNNER_CLASS.getDeclaredMethod("remainingMillis", long.class);
        m.setAccessible(true);
        return (long) m.invoke(null, deadlineNanos);
    }

    private static boolean invokeIsTimeoutLike(AssertionError e) throws Exception {
        Method m = RUNNER_CLASS.getDeclaredMethod("isTimeoutLike", AssertionError.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, e);
    }
}
