package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncAssert;
import se.deversity.asynctest.AsyncTest;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers async test-body support:
 *
 * <ul>
 *   <li>{@link AsyncAssert#awaitAsync(CompletionStage, Duration)} — the supported
 *       way to await an async chain from inside an @AsyncTest body, since JUnit
 *       Jupiter mandates void return types for @TestTemplate methods.</li>
 *   <li>The runner's CompletionStage handling in
 *       {@link ConcurrencyRunner} — exercised via an end-to-end @AsyncTest run
 *       that uses awaitAsync inside the body.</li>
 * </ul>
 */
class AsyncBodyRunnerTest {

    static final AtomicInteger ASYNC_COMPLETIONS = new AtomicInteger(0);

    // ---- AsyncAssert.awaitAsync unit tests ----

    @Test
    void awaitAsync_resolvesValue() {
        CompletableFuture<String> ok = CompletableFuture.completedFuture("hello");
        assertEquals("hello", AsyncAssert.awaitAsync(ok, Duration.ofSeconds(1)));
    }

    @Test
    void awaitAsync_unwrapsRuntimeException() {
        CompletableFuture<Void> bad = CompletableFuture.failedFuture(
                new IllegalStateException("boom"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AsyncAssert.awaitAsync(bad, Duration.ofSeconds(1)));
        assertEquals("boom", ex.getMessage());
    }

    @Test
    void awaitAsync_unwrapsAssertionError() {
        CompletableFuture<Void> bad = CompletableFuture.failedFuture(
                new AssertionError("expected: <1> but was: <2>"));
        AssertionError err = assertThrows(AssertionError.class,
                () -> AsyncAssert.awaitAsync(bad, Duration.ofSeconds(1)));
        // AssertionError is an Error → re-thrown directly, not wrapped.
        assertEquals("expected: <1> but was: <2>", err.getMessage());
    }

    @Test
    void awaitAsync_timesOut() {
        CompletableFuture<Void> never = new CompletableFuture<>();
        AssertionError err = assertThrows(AssertionError.class,
                () -> AsyncAssert.awaitAsync(never, Duration.ofMillis(50)));
        assertTrue(err.getMessage().contains("did not complete within"));
    }

    @Test
    void awaitAsync_nullArgs_throw() {
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssert.awaitAsync(null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssert.awaitAsync(CompletableFuture.completedFuture(1), null));
    }

    // ---- End-to-end @AsyncTest body using awaitAsync ----

    @Test
    void asyncBody_awaitedViaHelper_runsToCompletion() {
        ASYNC_COMPLETIONS.set(0);
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(SuccessFixture.class))
                .execute()
                .testEvents();
        tests.assertStatistics(s -> s.started(1).succeeded(1).failed(0));
        // 1 invocation x 4 threads = 4 async chains; all must have run before success was reported.
        assertEquals(4, ASYNC_COMPLETIONS.get(),
                "awaitAsync must block until each chain completes — without it the test would "
                        + "report success before the side-effect counter incremented");
    }

    @Test
    void asyncBody_failingChain_marksTestFailed() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(FailureFixture.class))
                .execute()
                .testEvents();
        tests.assertStatistics(s -> s.started(1).succeeded(0).failed(1));
    }

    // ---- Fixtures ----

    static class SuccessFixture {
        @AsyncTest(threads = 4, invocations = 1, timeoutMs = 10_000,
                detectAll = false, licenseMockMode = true)
        void awaitedAsyncBody() {
            CompletableFuture<Void> chain = CompletableFuture
                    .runAsync(() -> ASYNC_COMPLETIONS.incrementAndGet());
            AsyncAssert.awaitAsync(chain, Duration.ofSeconds(5));
        }
    }

    static class FailureFixture {
        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 10_000,
                detectAll = false, licenseMockMode = true)
        void awaitedAsyncFails() {
            CompletableFuture<Void> chain = CompletableFuture.runAsync(() -> {
                throw new IllegalStateException("boom-async");
            });
            AsyncAssert.awaitAsync(chain, Duration.ofSeconds(5));
        }
    }

    // ---- Round timeout cancels stranded workers instead of letting them linger ----

    /**
     * End-to-end regression coverage for the round-timeout path in
     * {@code ConcurrencyRunner.runSingleInvocationRound}: both worker threads clear the
     * barrier immediately and then sleep far longer than {@code timeoutMs}, so the round
     * is guaranteed to time out while both are still asleep. Confirms the whole run
     * finishes with both workers interrupted rather than a stranded thread lingering
     * (or the JVM getting stuck on a non-terminating worker).
     *
     * <p>{@code runSingleInvocationRound} now retains worker {@code Future}s and calls
     * {@code cancel(true)} on them as soon as the round's {@code CountDownLatch} times
     * out, rather than relying solely on {@code execute()}'s outer
     * {@code executor.shutdownNow()} to eventually interrupt them. Because
     * {@code EngineTestKit.execute()} blocks until the whole run (including that outer
     * {@code shutdownNow()}) has finished, this test can't isolate the fix's incremental
     * latency reduction in a portable way — both the fix and the pre-existing
     * {@code shutdownNow()} fallback would satisfy the assertion below. Its value is as a
     * regression guard: interruption must still happen reliably, well short of the 60s
     * sleep, after these changes.
     */
    @Test
    void roundTimeout_interruptsStrandedWorkers_promptly() throws InterruptedException {
        StrandedWorkerFixture.INTERRUPTED_LATCH = new CountDownLatch(2);

        long before = System.nanoTime();
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(StrandedWorkerFixture.class))
                .execute()
                .testEvents();
        tests.assertStatistics(s -> s.started(1).failed(1));
        long reportedAfterMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // Both stranded workers must have been interrupted (and counted down) well
        // before their 60s sleep would naturally elapse — cancel(true) should wake them
        // almost immediately once the round's own ~300ms timeout is detected.
        boolean bothInterrupted = StrandedWorkerFixture.INTERRUPTED_LATCH.await(10, TimeUnit.SECONDS);
        assertTrue(bothInterrupted,
                "both stranded worker threads must be interrupted promptly on round timeout, not left "
                        + "sleeping until the runner's outer executor.shutdownNow(); test already reported "
                        + "failure after " + reportedAfterMs + "ms");
    }

    static class StrandedWorkerFixture {
        static volatile CountDownLatch INTERRUPTED_LATCH;

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 300,
                detectAll = false, licenseMockMode = true)
        void hangsWellPastTimeout() {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                INTERRUPTED_LATCH.countDown();
                Thread.currentThread().interrupt();
            }
        }
    }
}
