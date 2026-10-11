package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCommonPoolBlockingDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureExceptionDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three {@code CompletableFuture} misuses, reported through the woven hooks alone.
 *
 * <p>The agent has substituted {@code supplyAsync}, {@code runAsync}, every dependent stage,
 * {@code join} and {@code get} since #741, for the happens-before model, and fed none of it to the
 * three detectors that name these misuses. Each case below goes through the hooks exactly as woven
 * code would and records nothing by hand.
 */
class AgentCompletableFutureHooksTest {

    private final List<ExecutorService> made = new ArrayList<>();

    @AfterEach
    void shutDown() throws InterruptedException {
        for (ExecutorService executor : made) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void install() {
        AsyncTestContext.install(
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
    }

    private static void awaitDone(CompletableFuture<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(future.isDone(), "the task did not finish");
    }

    // --- COMPLETABLE_FUTURE_EXCEPTIONS: a discarded async root that failed --------------------

    @Test
    @DisplayName("a discarded runAsync whose task threw is reported with the exception")
    void discardedFailedRunAsyncIsReported() throws InterruptedException {
        install();
        try {
            CompletableFuture<Void> future = AgentConcurrencyUtilHooks.runAsync(() -> {
                throw new IllegalArgumentException("invoice 7 malformed");
            });
            AgentConcurrencyUtilHooks.asyncResultDiscarded(future);
            awaitDone(future);

            CompletableFutureExceptionDetector.CompletableFutureExceptionReport report =
                    AsyncTestContext.completableFutureExceptionDetector().analyze();
            assertTrue(report.hasIssues(),
                    "the caller popped the future, so no handler, join or get can ever reach it, "
                            + "and its task failed. " + report);
            assertTrue(report.toString().contains("invoice 7 malformed"), report.toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a discarded supplyAsync that succeeded stays silent")
    void discardedSucceededSupplyAsyncIsSilent() throws InterruptedException {
        install();
        try {
            CompletableFuture<Object> future = AgentConcurrencyUtilHooks.supplyAsync(() -> "fine");
            AgentConcurrencyUtilHooks.asyncResultDiscarded(future);
            awaitDone(future);

            assertFalse(AsyncTestContext.completableFutureExceptionDetector().analyze().hasIssues(),
                    "fire-and-forget of a task that did not fail lost nothing");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a failed future the caller kept is not this path's concern")
    void keptFailedFutureIsNotReportedByThisPath() throws InterruptedException {
        install();
        try {
            CompletableFuture<Void> future = AgentConcurrencyUtilHooks.runAsync(() -> {
                throw new IllegalArgumentException("handled by the caller");
            });
            awaitDone(future);

            assertFalse(AsyncTestContext.completableFutureExceptionDetector().analyze().hasIssues(),
                    "the caller kept the future and may still join it; only a discarded one is "
                            + "certain to lose its exception");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    // --- COMPLETABLE_FUTURE_BLOCKING_CALLBACK -------------------------------------------------

    @Test
    @DisplayName("a callback that joins a future not yet complete is reported")
    void joinOnAPendingFutureInsideACallbackIsReported() throws Exception {
        install();
        try {
            CompletableFuture<Object> source = new CompletableFuture<>();
            CompletableFuture<Object> gate = new CompletableFuture<>();
            CountDownLatch completing = new CountDownLatch(1);
            CompletableFuture<Object> stage = AgentConcurrencyUtilHooks.thenApply(source,
                    v -> AgentConcurrencyUtilHooks.join(gate));

            Thread completer = new Thread(() -> {
                completing.countDown();
                source.complete("go"); // runs the callback inline, which blocks on gate
            }, "completer");
            completer.start();
            completing.await();
            Thread.sleep(100); // lets the callback reach the join
            gate.complete("open");
            completer.join(5_000);
            assertEquals("open", stage.get(5, TimeUnit.SECONDS));

            CompletableFutureBlockingCallbackDetector.Report report =
                    AsyncTestContext.cfBlockingCallbackDetector().analyze();
            assertTrue(report.hasIssues(),
                    "the callback ran on the completing thread and parked it on a future that "
                            + "was not complete. " + report);
            assertTrue(report.toString().contains("CompletableFuture.join"), report.toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("joining futures allOf already completed, inside its callback, stays silent")
    void allOfThenJoinIdiomIsSilent() throws Exception {
        install();
        try {
            CompletableFuture<Object> a = CompletableFuture.completedFuture(1);
            CompletableFuture<Object> b = CompletableFuture.completedFuture(2);
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> all = (CompletableFuture<Object>) (CompletableFuture<?>)
                    CompletableFuture.allOf(a, b);
            CompletableFuture<Object> sum = AgentConcurrencyUtilHooks.thenApply(all,
                    v -> (Integer) AgentConcurrencyUtilHooks.join(a)
                            + (Integer) AgentConcurrencyUtilHooks.join(b));
            assertEquals(3, sum.get(5, TimeUnit.SECONDS));

            assertFalse(AsyncTestContext.cfBlockingCallbackDetector().analyze().hasIssues(),
                    "allOf(a, b).thenApply(v -> a.join() + b.join()) is the documented way to "
                            + "combine results; both joins return at once, so nothing blocked");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    // --- CF_COMMON_POOL_BLOCKING --------------------------------------------------------------

    @Test
    @DisplayName("a supplyAsync handed the common pool that sleeps is reported")
    void sleepOnTheExplicitCommonPoolIsReported() throws Exception {
        install();
        try {
            CompletableFuture<Object> future = AgentConcurrencyUtilHooks.supplyAsync(() -> {
                try {
                    AgentSleepHooks.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "slept";
            }, ForkJoinPool.commonPool());
            assertEquals("slept", future.get(5, TimeUnit.SECONDS));

            assertTrue(AsyncTestContext.cfCommonPoolBlockingDetector().analyze().hasIssues(),
                    "the common pool was named explicitly, so the task ran on a common-pool "
                            + "worker whatever its parallelism, and parked it in Thread.sleep");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a supplyAsync with no executor that sleeps is reported")
    void sleepOnTheCommonPoolIsReported() throws Exception {
        assumeTrue(ForkJoinPool.getCommonPoolParallelism() > 1,
                "with parallelism 1 the JDK runs an executor-less async task on a thread of its "
                        + "own, which starves no pool; the explicit-pool case covers this machine");
        install();
        try {
            CompletableFuture<Object> future = AgentConcurrencyUtilHooks.supplyAsync(() -> {
                try {
                    AgentSleepHooks.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "slept";
            });
            assertEquals("slept", future.get(5, TimeUnit.SECONDS));

            CompletableFutureCommonPoolBlockingDetector.CompletableFutureCommonPoolBlockingReport report =
                    AsyncTestContext.cfCommonPoolBlockingDetector().analyze();
            assertTrue(report.hasIssues(),
                    "a task submitted without an executor ran on the common ForkJoinPool and "
                            + "parked its worker in Thread.sleep. " + report);
            assertTrue(report.toString().contains("Thread.sleep"), report.toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the same sleep on a dedicated executor stays silent")
    void sleepOnADedicatedExecutorIsSilent() throws Exception {
        ExecutorService own = Executors.newSingleThreadExecutor();
        made.add(own);
        install();
        try {
            CompletableFuture<Object> future = AgentConcurrencyUtilHooks.supplyAsync(() -> {
                try {
                    AgentSleepHooks.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "slept";
            }, own);
            assertEquals("slept", future.get(5, TimeUnit.SECONDS));

            assertFalse(AsyncTestContext.cfCommonPoolBlockingDetector().analyze().hasIssues(),
                    "supplying an executor for blocking work is the fix the detector recommends");
        } finally {
            AsyncTestContext.uninstall();
        }
    }
}
