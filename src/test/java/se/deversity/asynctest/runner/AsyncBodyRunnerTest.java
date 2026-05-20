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
}
