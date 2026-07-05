package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link se.deversity.asynctest.runner.ConcurrencyRunner} helper methods.
 *
 * <p>ConcurrencyRunner's helper methods are private static; we access them via reflection
 * so that we can keep them private (they are not API) while still having direct test coverage.
 */
class ConcurrencyRunnerTest {

    private static final Class<?> RUNNER_CLASS =
            se.deversity.asynctest.runner.ConcurrencyRunner.class;

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

    // ---- createBarrier: CyclicBarrier path now has a bounded wait ----
    //
    // Before this fix, the CyclicBarrier path used the no-arg await(), which blocks a
    // party forever if another party never arrives (e.g. a peer worker died before
    // calling arrive()). These tests exercise createBarrier's returned ContentionBarrier
    // directly via reflection (it's a private nested type, not part of the public API).

    @Test
    void createBarrier_cyclicPath_missingPartyFailsFastInsteadOfHangingForever() throws Exception {
        // Force the CyclicBarrier path regardless of ambient system properties — the
        // SpinContentionBarrier path has no timeout at all and would hang this test.
        String previous = System.getProperty("async-test.spin-barrier.enabled");
        System.clearProperty("async-test.spin-barrier.enabled");
        try {
            Object barrier = invokeCreateBarrier(2, 150L); // 2 participants, 150ms timeout
            Method arrive = findNoArgMethod(barrier.getClass(), "arrive");

            long startNanos = System.nanoTime();
            InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                    () -> arrive.invoke(barrier));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            Throwable cause = ite.getCause();
            assertTrue(cause instanceof TimeoutException || cause instanceof BrokenBarrierException,
                    "a barrier missing a party must fail with Timeout/BrokenBarrier instead of hanging forever: " + cause);
            assertTrue(elapsedMs < 5_000,
                    "arrive() must respect the configured barrier timeout rather than block indefinitely; took " + elapsedMs + "ms");
        } finally {
            restoreProperty("async-test.spin-barrier.enabled", previous);
        }
    }

    @Test
    void createBarrier_cyclicPath_healthyRoundStillReleasesAllParties() throws Exception {
        String previous = System.getProperty("async-test.spin-barrier.enabled");
        System.clearProperty("async-test.spin-barrier.enabled");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Object barrier = invokeCreateBarrier(2, 5_000L); // generous timeout, well within test bounds
            Method arrive = findNoArgMethod(barrier.getClass(), "arrive");

            Future<?> f1 = pool.submit(() -> invokeArriveUnchecked(arrive, barrier));
            Future<?> f2 = pool.submit(() -> invokeArriveUnchecked(arrive, barrier));
            // Both parties arrive promptly; neither call should throw or block past a
            // couple of seconds — identical to the untimed no-arg await() for a healthy round.
            f1.get(3, TimeUnit.SECONDS);
            f2.get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            restoreProperty("async-test.spin-barrier.enabled", previous);
        }
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    // ---- Phase2Analysis: memoizes analyzeAll() so it runs at most once per execute() ----

    @Test
    void phase2Analysis_getIsMemoized_returnsSameListOnRepeatedCalls() throws Exception {
        AsyncTestConfig cfg = AsyncTestConfig.builder().build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);

        Class<?> phase2AnalysisClass = null;
        for (Class<?> nested : RUNNER_CLASS.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("Phase2Analysis")) {
                phase2AnalysisClass = nested;
                break;
            }
        }
        assertNotNull(phase2AnalysisClass, "ConcurrencyRunner must declare a Phase2Analysis nested type");

        Constructor<?> ctor = phase2AnalysisClass.getDeclaredConstructor(AsyncTestContext.class);
        ctor.setAccessible(true);
        Object phase2Analysis = ctor.newInstance(ctx);

        Method get = phase2AnalysisClass.getDeclaredMethod("get");
        get.setAccessible(true);

        Object first = get.invoke(phase2Analysis);
        Object second = get.invoke(phase2Analysis);

        assertSame(first, second,
                "Phase2Analysis.get() must memoize analyzeAll()'s result — DetectorRegistry.analyzeAll() "
                        + "builds a fresh List each call, so two distinct calls would never be assertSame");
    }

    // ---- Reflection helpers ----

    private static Object invokeCreateBarrier(int threads, long timeoutMs) throws Exception {
        Method m = RUNNER_CLASS.getDeclaredMethod("createBarrier", int.class, long.class);
        m.setAccessible(true);
        return m.invoke(null, threads, timeoutMs);
    }

    private static Method findNoArgMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new AssertionError("No public no-arg method named '" + name + "' on " + clazz);
    }

    private static void invokeArriveUnchecked(Method arrive, Object barrier) {
        try {
            arrive.invoke(barrier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
