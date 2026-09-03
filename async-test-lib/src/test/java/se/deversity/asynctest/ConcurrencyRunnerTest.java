package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
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

    @Test
    void buildMultiFailureError_identicalFailures_areGroupedWithACount() throws Exception {
        // The same assertion failing on 3 of 4 threads is one defect, not three. Listing it
        // three times buries the fourth, distinct failure — the one worth reading.
        AssertionError a1 = new AssertionError("expected 400 but was 399");
        AssertionError a2 = new AssertionError("expected 400 but was 399");
        AssertionError a3 = new AssertionError("expected 400 but was 399");
        AssertionError other = new AssertionError("lock still held");

        AssertionError result = invokeBuildMultiFailure(List.of(a1, a2, a3, other));

        String message = result.getMessage();
        assertTrue(message.contains("4 concurrent thread(s) failed"),
                "The thread count must stay the real one: " + message);
        assertTrue(message.contains("x3"), "Repeats must be counted, not repeated: " + message);
        assertEquals(2, message.lines().filter(line -> line.strip().startsWith("[")).count(),
                "One line per distinct failure: " + message);
        assertTrue(message.contains("lock still held"),
                "The distinct failure must survive the grouping: " + message);
        assertSame(a1, result.getCause());

        Throwable[] suppressed = result.getSuppressed();
        assertEquals(1, suppressed.length,
                "One representative per distinct failure, so N identical traces do not drown the report");
        assertSame(other, suppressed[0]);
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

    // ---- isTimeoutLike: routes on the runner's own RoundTimeoutError marker type ----
    //
    // Previously this matched any AssertionError whose message contained "timed out",
    // which misclassified user assertion failures that happened to mention those words:
    // the user's failure was rewrapped as "Test timed out after ...", the replay-seed
    // line was skipped, and listeners got onTimeout instead of onTestFailed.

    @Test
    void isTimeoutLike_runnerRoundTimeoutError_returnsTrue() throws Exception {
        assertTrue(invokeIsTimeoutLike(newRoundTimeoutError("Invocation round timed out: 0/2")));
    }

    @Test
    void isTimeoutLike_userErrorMentioningTimedOut_returnsFalse() throws Exception {
        AssertionError e = new AssertionError("latch timed out after 5000ms");
        assertFalse(invokeIsTimeoutLike(e),
                "a user assertion mentioning 'timed out' must not be treated as a harness timeout");
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
            Object barrier = invokeCreateBarrier(2, 150L, false); // 2 participants, 150ms timeout
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
            Object barrier = invokeCreateBarrier(2, 5_000L, false); // generous timeout, well within test bounds
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

    @Test
    void createBarrier_virtualThreads_forcesCyclicBarrierEvenWhenSpinRequested() throws Exception {
        String previous = System.getProperty("async-test.spin-barrier.enabled");
        System.setProperty("async-test.spin-barrier.enabled", "true");
        try {
            // With useVirtualThreads=true the spin barrier must be ignored: neither
            // Thread.onSpinWait() nor Thread.interrupted() is a virtual-thread scheduling
            // point, so with more participants than carriers the spinners occupy every
            // carrier and the missing parties can never mount to arrive — a livelock that
            // burns the whole round budget and reports a spurious timeout with zero
            // detector activity. The CyclicBarrier path is provable from the outside: a
            // missing party makes arrive() throw Timeout/BrokenBarrier at ~150ms, where
            // the spin path (which has no timed await at all) would spin forever.
            Object barrier = invokeCreateBarrier(2, 150L, true);
            Method arrive = findNoArgMethod(barrier.getClass(), "arrive");

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                        () -> arrive.invoke(barrier));
                Throwable cause = ite.getCause();
                assertTrue(cause instanceof TimeoutException || cause instanceof BrokenBarrierException,
                        "virtual-thread runs must get the timed CyclicBarrier path even with the "
                                + "spin-barrier property set, got: " + cause);
            });
        } finally {
            restoreProperty("async-test.spin-barrier.enabled", previous);
        }
    }

    // ---- execute(): user failures mentioning "timed out" keep their identity ----

    /** Thrown by {@link TimedOutMessageFixture}; static so the test can assertSame on it. */
    private static final AssertionError USER_TIMED_OUT_FAILURE =
            new AssertionError("operation timed out unexpectedly (user assertion)");

    static final class TimedOutMessageFixture {
        private void failsMentioningTimedOut() {
            throw USER_TIMED_OUT_FAILURE;
        }
    }

    @Test
    void execute_userAssertionMentioningTimedOut_keepsItsIdentityAndListenersSeeFailure() throws Exception {
        String previousLicense = System.getProperty("license.mock.mode");
        System.setProperty("license.mock.mode", "true");
        java.util.concurrent.atomic.AtomicBoolean timeoutFired = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean failureFired = new java.util.concurrent.atomic.AtomicBoolean();
        try (AsyncTestListenerRegistry.Registration ignored = AsyncTestListenerRegistry.registerScoped(
                new AsyncTestListener() {
                    @Override public void onTimeout(long timeoutMs) { timeoutFired.set(true); }
                    @Override public void onTestFailed(Throwable cause) { failureFired.set(true); }
                })) {
            AsyncTestConfig config = AsyncTestConfig.builder()
                    .threads(1).invocations(1).useVirtualThreads(false)
                    .timeoutMs(10_000).detectAll(false).detectDeadlocks(false)
                    .build();
            TimedOutMessageFixture fixture = new TimedOutMessageFixture();
            Method method = TimedOutMessageFixture.class.getDeclaredMethod("failsMentioningTimedOut");
            FakeInvocationContext context = new FakeInvocationContext(fixture, method, List.of());

            AssertionError thrown = assertThrows(AssertionError.class,
                    () -> se.deversity.asynctest.runner.ConcurrencyRunner.execute(context, config));

            assertSame(USER_TIMED_OUT_FAILURE, thrown,
                    "a user assertion mentioning 'timed out' must propagate unchanged, not be "
                            + "rewrapped as a harness timeout");
            assertTrue(failureFired.get(), "listeners must see onTestFailed for a user failure");
            assertFalse(timeoutFired.get(), "listeners must NOT see onTimeout for a user failure");
        } finally {
            restoreProperty("license.mock.mode", previousLicense);
        }
    }

    // ---- execute(): timeout path quiesces cancelled workers before analyzing ----

    /** Set when {@link StubbornFixture}'s body finally unwinds; reset by the test. */
    private static final java.util.concurrent.atomic.AtomicBoolean STUBBORN_WORKER_EXITED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * A body that outlives its round budget and shrugs off the cancel interrupt for
     * ~1000ms before exiting — user code that unwinds slowly after cancellation. While it
     * runs it is exactly the worker whose detector writes a concurrent analysis would race.
     */
    static final class StubbornFixture {
        private void ignoresInterruptsBriefly() {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                    // Deliberately swallowed: the worker must keep running after cancel(true).
                }
            }
            STUBBORN_WORKER_EXITED.set(true);
        }
    }

    @Test
    void execute_roundTimeout_quiescesCancelledWorkersBeforeReporting() throws Exception {
        String previousLicense = System.getProperty("license.mock.mode");
        String previousMultiplier = System.getProperty("async-test.timeout.multiplier");
        System.setProperty("license.mock.mode", "true");
        // Pin to 1: CI exports ASYNC_TEST_TIMEOUT_MULTIPLIER on slow legs, and the scaled
        // budget must stay far below the fixture's 1000ms unwind so the timeout fires first.
        System.setProperty("async-test.timeout.multiplier", "1");
        STUBBORN_WORKER_EXITED.set(false);
        java.util.concurrent.atomic.AtomicBoolean workerHadExitedWhenTimeoutFired =
                new java.util.concurrent.atomic.AtomicBoolean();
        try (AsyncTestListenerRegistry.Registration ignored = AsyncTestListenerRegistry.registerScoped(
                new AsyncTestListener() {
                    @Override public void onTimeout(long timeoutMs) {
                        // timeoutError() fires this immediately before the detector reports:
                        // by now quiesceWorkers() must have let the cancelled worker finish.
                        workerHadExitedWhenTimeoutFired.set(STUBBORN_WORKER_EXITED.get());
                    }
                })) {
            AsyncTestConfig config = AsyncTestConfig.builder()
                    .threads(1).invocations(1).useVirtualThreads(false)
                    .timeoutMs(100).detectAll(false).detectDeadlocks(false)
                    .build();
            StubbornFixture fixture = new StubbornFixture();
            Method method = StubbornFixture.class.getDeclaredMethod("ignoresInterruptsBriefly");
            FakeInvocationContext context = new FakeInvocationContext(fixture, method, List.of());

            AssertionError timeout = assertThrows(AssertionError.class,
                    () -> se.deversity.asynctest.runner.ConcurrencyRunner.execute(context, config));
            assertTrue(timeout.getMessage() != null && timeout.getMessage().contains("timed out"),
                    "the 100ms budget must time out against the ~1000ms stubborn body: " + timeout.getMessage());

            assertTrue(workerHadExitedWhenTimeoutFired.get(),
                    "reporting (onTimeout + detector analysis) must not begin until cancelled "
                            + "workers stopped running — analyzing concurrently tears detector state "
                            + "and loses findings on exactly the timeout runs that need them");
        } finally {
            restoreProperty("async-test.timeout.multiplier", previousMultiplier);
            restoreProperty("license.mock.mode", previousLicense);
        }
    }

    @Test
    void quiesceGracePropertyBoundsTheWaitForStuckWorkers() throws Exception {
        String previousLicense = System.getProperty("license.mock.mode");
        String previousMultiplier = System.getProperty("async-test.timeout.multiplier");
        String previousGrace = System.getProperty("async-test.quiesce.grace.ms");
        System.setProperty("license.mock.mode", "true");
        System.setProperty("async-test.timeout.multiplier", "1");
        // 100ms grace against a worker that shrugs off interrupts for ~1000ms: reporting
        // must proceed once the bound expires instead of waiting the full unwind out. The
        // 700ms buffer between (timeout + grace) and the worker's unwind time is deliberately
        // generous — a tighter margin flaked on loaded CI runners (macOS/JDK21 in particular).
        System.setProperty("async-test.quiesce.grace.ms", "100");
        STUBBORN_WORKER_EXITED.set(false);
        java.util.concurrent.atomic.AtomicBoolean workerHadExitedWhenTimeoutFired =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        try (AsyncTestListenerRegistry.Registration ignored = AsyncTestListenerRegistry.registerScoped(
                new AsyncTestListener() {
                    @Override public void onTimeout(long timeoutMs) {
                        workerHadExitedWhenTimeoutFired.set(STUBBORN_WORKER_EXITED.get());
                    }
                })) {
            AsyncTestConfig config = AsyncTestConfig.builder()
                    .threads(1).invocations(1).useVirtualThreads(false)
                    .timeoutMs(200).detectAll(false).detectDeadlocks(false)
                    .build();
            StubbornFixture fixture = new StubbornFixture();
            Method method = StubbornFixture.class.getDeclaredMethod("ignoresInterruptsBriefly");
            assertThrows(AssertionError.class, () -> se.deversity.asynctest.runner.ConcurrencyRunner
                    .execute(new FakeInvocationContext(fixture, method, List.of()), config));

            assertFalse(workerHadExitedWhenTimeoutFired.get(),
                    "with a 100ms grace the runner must proceed to reporting while the stubborn "
                            + "worker is still unwinding — the property bounds the wait");
        } finally {
            restoreProperty("async-test.quiesce.grace.ms", previousGrace);
            restoreProperty("async-test.timeout.multiplier", previousMultiplier);
            restoreProperty("license.mock.mode", previousLicense);
        }
    }

    // ---- execute(): a failed round quiesces cancelled workers before the after-hooks ----

    /** Set by {@link StubbornWithAfterHookFixture}'s hook: had the worker exited when it ran? */
    private static final java.util.concurrent.atomic.AtomicBoolean AFTER_HOOK_SAW_WORKER_EXITED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The stubborn body again, with an {@code @AfterEachInvocation} hook that records whether the
     * worker had exited by the time the hook ran.
     *
     * <p>The runner has two quiesce points on a timed-out round: one in the round's own
     * {@code finally}, before the after-hooks, and one in the {@code AssertionError} branch,
     * before reporting. {@code execute_roundTimeout_quiescesCancelledWorkersBeforeReporting}
     * observes at {@code onTimeout}, which comes after both, so deleting either one alone
     * leaves the other to satisfy it, and PIT reported both surviving (#426). The after-hook is
     * the one observation point that only the first quiesce protects.
     */
    static final class StubbornWithAfterHookFixture {
        private void ignoresInterruptsBriefly() {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                    // Deliberately swallowed: the worker must keep running after cancel(true).
                }
            }
            STUBBORN_WORKER_EXITED.set(true);
        }

        @AfterEachInvocation
        void afterEach() {
            AFTER_HOOK_SAW_WORKER_EXITED.set(STUBBORN_WORKER_EXITED.get());
        }
    }

    @Test
    void execute_roundTimeout_quiescesCancelledWorkersBeforeAfterHooks() throws Exception {
        String previousLicense = System.getProperty("license.mock.mode");
        String previousMultiplier = System.getProperty("async-test.timeout.multiplier");
        System.setProperty("license.mock.mode", "true");
        System.setProperty("async-test.timeout.multiplier", "1");
        STUBBORN_WORKER_EXITED.set(false);
        AFTER_HOOK_SAW_WORKER_EXITED.set(false);
        try {
            // 100ms budget, ~1000ms unwind, default 2000ms grace: the hook can only see an exited
            // worker if the runner waited for it. Without the quiesce the hook runs within
            // milliseconds of the cancel, some 900ms before the worker is done.
            AsyncTestConfig config = AsyncTestConfig.builder()
                    .threads(1).invocations(1).useVirtualThreads(false)
                    .timeoutMs(100).detectAll(false).detectDeadlocks(false)
                    .build();
            StubbornWithAfterHookFixture fixture = new StubbornWithAfterHookFixture();
            Method method = StubbornWithAfterHookFixture.class.getDeclaredMethod("ignoresInterruptsBriefly");
            assertThrows(AssertionError.class, () -> se.deversity.asynctest.runner.ConcurrencyRunner
                    .execute(new FakeInvocationContext(fixture, method, List.of()), config));

            assertTrue(AFTER_HOOK_SAW_WORKER_EXITED.get(),
                    "the after-hook ran while the cancelled worker was still inside the test body. "
                            + "A hook tearing down state a live worker is touching is the failure "
                            + "the round's quiesce exists to prevent, and a hook that blocked on "
                            + "worker-held state would hang the runner with nothing left to bound "
                            + "it (#426)");
        } finally {
            restoreProperty("async-test.timeout.multiplier", previousMultiplier);
            restoreProperty("license.mock.mode", previousLicense);
        }
    }

    // ---- Worker threads carry the harness name prefix ----

    /** Set by {@link NameCaptureFixture}; reset per test iteration. */
    private static final java.util.concurrent.atomic.AtomicReference<String> CAPTURED_WORKER_NAME =
            new java.util.concurrent.atomic.AtomicReference<>();

    static final class NameCaptureFixture {
        private void captureWorkerName() {
            CAPTURED_WORKER_NAME.set(Thread.currentThread().getName());
        }
    }

    @Test
    void workerThreadsCarryTheHarnessNamePrefix() throws Throwable {
        String previousLicense = System.getProperty("license.mock.mode");
        System.setProperty("license.mock.mode", "true");
        try {
            for (boolean virtualThreads : new boolean[] {false, true}) {
                CAPTURED_WORKER_NAME.set(null);
                AsyncTestConfig config = AsyncTestConfig.builder()
                        .threads(1).invocations(1).useVirtualThreads(virtualThreads)
                        .timeoutMs(10_000).detectAll(false).detectDeadlocks(false)
                        .build();
                NameCaptureFixture fixture = new NameCaptureFixture();
                Method method = NameCaptureFixture.class.getDeclaredMethod("captureWorkerName");
                se.deversity.asynctest.runner.ConcurrencyRunner.execute(
                        new FakeInvocationContext(fixture, method, List.of()), config);

                String name = CAPTURED_WORKER_NAME.get();
                assertNotNull(name, "the worker must have run (virtualThreads=" + virtualThreads + ")");
                assertTrue(name.startsWith("async-test-worker-"),
                        "worker threads must carry the harness prefix so thread dumps and the "
                                + "quiesce stack dump identify them (virtualThreads=" + virtualThreads
                                + ", name=" + name + ")");
            }
        } finally {
            restoreProperty("license.mock.mode", previousLicense);
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

    // ---- resolveTimeoutMultiplier: CI timeout-scaling knob ----
    //
    // Motivation: an experimental macOS CI leg (3-core runners) timed out a fixture test at
    // its 5000ms @AsyncTest budget purely because ~121 detectors' setup ate into the budget
    // on a slow runner — not a real concurrency bug. Rather than bumping every fixture
    // annotation, ConcurrencyRunner.execute now scales config.timeoutMs by a multiplier
    // resolved from -Dasync-test.timeout.multiplier (falling back to the
    // ASYNC_TEST_TIMEOUT_MULTIPLIER env var, then 1.0). These tests cover the sysprop path
    // directly: (1) resolveTimeoutMultiplier's own parsing/fallback logic in isolation, and
    // (2) an end-to-end execute() run demonstrating the multiplier actually changes whether
    // a slow test body passes.

    @Test
    void resolveTimeoutMultiplier_validSysprop_returnsParsedValue() throws Exception {
        String previous = System.getProperty("async-test.timeout.multiplier");
        try {
            System.setProperty("async-test.timeout.multiplier", "2.5");
            assertEquals(2.5, invokeResolveTimeoutMultiplier(), 0.0001);
        } finally {
            restoreProperty("async-test.timeout.multiplier", previous);
        }
    }

    @Test
    void resolveTimeoutMultiplier_missingSysprop_fallsBackToEnvVarThenOne() throws Exception {
        String previous = System.getProperty("async-test.timeout.multiplier");
        try {
            System.clearProperty("async-test.timeout.multiplier");
            // CI sets ASYNC_TEST_TIMEOUT_MULTIPLIER on slow-runner legs, and the env
            // cannot be cleared from within the JVM — so the expectation mirrors the
            // documented fallback chain: a valid positive env var wins, else 1.0.
            double expected = 1.0;
            String env = System.getenv("ASYNC_TEST_TIMEOUT_MULTIPLIER");
            if (env != null && !env.isBlank()) {
                try {
                    double parsed = Double.parseDouble(env.trim());
                    if (parsed > 0.0) {
                        expected = parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // malformed env var — resolver falls back to 1.0
                }
            }
            assertEquals(expected, invokeResolveTimeoutMultiplier(), 0.0001);
        } finally {
            restoreProperty("async-test.timeout.multiplier", previous);
        }
    }

    @Test
    void resolveTimeoutMultiplier_nonNumericSysprop_fallsBackToOne() throws Exception {
        assertMultiplierFallsBackToOne("abc");
    }

    @Test
    void resolveTimeoutMultiplier_zeroSysprop_fallsBackToOne() throws Exception {
        assertMultiplierFallsBackToOne("0");
    }

    @Test
    void resolveTimeoutMultiplier_negativeSysprop_fallsBackToOne() throws Exception {
        assertMultiplierFallsBackToOne("-1");
    }

    private static void assertMultiplierFallsBackToOne(String invalidValue) throws Exception {
        String previous = System.getProperty("async-test.timeout.multiplier");
        try {
            System.setProperty("async-test.timeout.multiplier", invalidValue);
            assertEquals(1.0, invokeResolveTimeoutMultiplier(), 0.0001,
                    "invalid multiplier '" + invalidValue + "' must fall back to 1.0 rather than throw");
        } finally {
            restoreProperty("async-test.timeout.multiplier", previous);
        }
    }

    // ---- execute(): the resolved multiplier actually scales the effective timeout budget ----

    @Test
    void execute_timeoutMultiplierSysprop_scalesEffectiveTimeoutBudget() throws Throwable {
        // ConcurrencyRunner.execute's first call is LicenseGuard.check(config); mock mode
        // avoids a real license lookup, matching AsyncTestInvocationInterceptorTest's setup.
        String previousLicenseMockMode = System.getProperty("license.mock.mode");
        String previousMultiplier = System.getProperty("async-test.timeout.multiplier");
        System.setProperty("license.mock.mode", "true");
        try {
            // detectAll/detectDeadlocks off so the only work per round is the reflective
            // invoke — keeps this test fast and isolates the timing to the sleep itself.
            AsyncTestConfig tightConfig = AsyncTestConfig.builder()
                    .threads(1)
                    .invocations(1)
                    .useVirtualThreads(false)
                    .timeoutMs(30)
                    .detectAll(false)
                    .detectDeadlocks(false)
                    .build();

            SleepFixture fixture = new SleepFixture();
            Method method = SleepFixture.class.getDeclaredMethod("sleepBriefly");
            FakeInvocationContext context = new FakeInvocationContext(fixture, method, List.of());

            // Pin the multiplier to 1 explicitly rather than clearing the property:
            // resolveTimeoutMultiplier() falls back to the ASYNC_TEST_TIMEOUT_MULTIPLIER
            // env var, which CI sets to 3 on macOS/Windows runners — clearing the sysprop
            // there silently scaled the budget to 90ms and shrank the timeout margin to
            // 30ms, which a single latch park-overshoot on a loaded runner can absorb.
            // A 30ms budget vs a ~300ms test body must fail with a timeout, not a false pass.
            System.setProperty("async-test.timeout.multiplier", "1");
            AssertionError timeout = assertThrows(AssertionError.class,
                    () -> se.deversity.asynctest.runner.ConcurrencyRunner.execute(context, tightConfig));
            assertTrue(timeout.getMessage() != null && timeout.getMessage().contains("timed out"),
                    "a 30ms budget must time out against a ~300ms test body at multiplier=1.0: "
                            + timeout.getMessage());

            // Scaling by 50x (effective 1500ms) comfortably covers the same ~300ms test
            // body even on a stalled CI runner -- same config, same test body, only the
            // multiplier changed.
            System.setProperty("async-test.timeout.multiplier", "50");
            assertDoesNotThrow(
                    () -> se.deversity.asynctest.runner.ConcurrencyRunner.execute(context, tightConfig),
                    "a 50x multiplier must scale the 30ms budget enough to cover the ~300ms test body");
        } finally {
            restoreProperty("async-test.timeout.multiplier", previousMultiplier);
            restoreProperty("license.mock.mode", previousLicenseMockMode);
        }
    }

    /**
     * Minimal {@code @AsyncTest}-free fixture: a body that sleeps long enough to exercise
     * timeouts. 300ms keeps a wide margin on both sides: far above the 30ms budget in the
     * timeout phase (a latch park-overshoot cannot bridge it), far below the 1500ms scaled
     * budget in the pass phase. The timeout phase does not wait the sleep out — the round
     * interrupts workers as soon as the 30ms budget expires.
     */
    static final class SleepFixture {
        private void sleepBriefly() throws InterruptedException {
            Thread.sleep(300);
        }
    }

    /**
     * Hand-rolled fake (no mocking library declared in this project's pom.xml — see
     * AsyncTestInvocationInterceptorTest's class Javadoc for the same rationale).
     */
    static final class FakeInvocationContext implements ReflectiveInvocationContext<Method> {
        private final Object target;
        private final Method method;
        private final List<Object> arguments;

        FakeInvocationContext(Object target, Method method, List<Object> arguments) {
            this.target = target;
            this.method = method;
            this.arguments = arguments;
        }

        @Override
        public Class<?> getTargetClass() {
            return target.getClass();
        }

        @Override
        public Method getExecutable() {
            return method;
        }

        @Override
        public List<Object> getArguments() {
            return arguments;
        }

        @Override
        public Optional<Object> getTarget() {
            return Optional.of(target);
        }
    }

    private static double invokeResolveTimeoutMultiplier() throws Exception {
        Method m = RUNNER_CLASS.getDeclaredMethod("resolveTimeoutMultiplier");
        m.setAccessible(true);
        return (double) m.invoke(null);
    }

    // ---- Reflection helpers ----

    private static Object invokeCreateBarrier(int threads, long timeoutMs,
                                              boolean useVirtualThreads) throws Exception {
        Method m = RUNNER_CLASS.getDeclaredMethod("createBarrier", int.class, long.class, boolean.class);
        m.setAccessible(true);
        return m.invoke(null, threads, timeoutMs, useVirtualThreads);
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

    /**
     * Builds the runner's package-private RoundTimeoutError marker via reflection
     * (this test class lives outside the runner package on purpose).
     */
    private static AssertionError newRoundTimeoutError(String message) throws Exception {
        for (Class<?> nested : RUNNER_CLASS.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("RoundTimeoutError")) {
                Constructor<?> ctor = nested.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                return (AssertionError) ctor.newInstance(message);
            }
        }
        throw new AssertionError("ConcurrencyRunner must declare a RoundTimeoutError nested type");
    }
}
