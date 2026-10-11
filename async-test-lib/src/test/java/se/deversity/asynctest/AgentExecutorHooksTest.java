package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ExecutorShutdownDetector;
import se.deversity.asynctest.diagnostics.FutureIgnoredDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executor ownership and discarded futures, reported through the woven hooks alone.
 *
 * <p>{@code EXECUTOR_SHUTDOWN} reports only an executor declared as owned, and the agent could
 * not declare one: the {@code Executors} factories are static calls the weaver did not rewrite
 * (#386), and feeding {@code submit} alone left every executor undeclared and the detector silent
 * (#387). The factories are woven now, and an executor a run thread creates is the run's own.
 * {@code FUTURE_IGNORED} compared a recorded submit with a recorded inspection; a {@code submit}
 * whose result the caller pops can never be inspected, and the agent sees that {@code POP}.
 */
class AgentExecutorHooksTest {

    private final List<ExecutorService> made = new ArrayList<>();

    @AfterEach
    void shutDownWhatTheTestsMade() throws InterruptedException {
        for (ExecutorService executor : made) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static AsyncTestContext installed() {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        AsyncTestContext.install(context);
        return context;
    }

    private <E extends ExecutorService> E kept(E executor) {
        made.add(executor);
        return executor;
    }

    // --- EXECUTOR_SHUTDOWN --------------------------------------------------------------------

    @Test
    @DisplayName("a pool a run thread created, used and never shut down is reported")
    void poolCreatedInTheRunAndLeftRunningIsReported() {
        installed();
        try {
            ExecutorService pool = kept(AgentConcurrencyUtilHooks.newFixedThreadPool(2));
            AgentConcurrencyUtilHooks.submit(pool, (Runnable) () -> { });

            ExecutorShutdownDetector.ExecutorShutdownReport report =
                    AsyncTestContext.executorShutdownDetector().analyze();
            assertTrue(report.hasIssues(),
                    "the pool was made by a woven factory on a run thread, so the run owns it, "
                            + "and it is still running at analysis; the woven calls are the only "
                            + "feed here. " + report);
            assertTrue(report.toString().contains("shutdown() never called"), report.toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a pool the run created and shut down with awaitTermination stays silent")
    void poolShutDownAndAwaitedIsSilent() throws InterruptedException {
        installed();
        try {
            ExecutorService pool = kept(AgentConcurrencyUtilHooks.newCachedThreadPool());
            AgentConcurrencyUtilHooks.submit(pool, (Runnable) () -> { });
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            assertFalse(AsyncTestContext.executorShutdownDetector().analyze().hasIssues(),
                    "the pool was closed the documented way before analysis");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** A pool held in a static field, built the first time the class is used. */
    static final class StaticPool {
        static final ExecutorService POOL = AgentConcurrencyUtilHooks.newSingleThreadExecutor();

        private StaticPool() {
        }
    }

    @Test
    @DisplayName("a pool built in a static initializer is not the run's to shut down")
    void poolFromAStaticInitializerIsSilent() {
        installed();
        try {
            ExecutorService pool = kept(StaticPool.POOL);
            AgentConcurrencyUtilHooks.submit(pool, (Runnable) () -> { });

            assertFalse(AsyncTestContext.executorShutdownDetector().analyze().hasIssues(),
                    "a static pool is shared by design and lives as long as its class; the run "
                            + "happened to load the class, which makes it neither the creator's "
                            + "nor the run's to close (#387)");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a pool created outside the run and used inside it stays silent")
    void poolCreatedOutsideTheRunIsSilent() {
        ExecutorService handedIn = kept(AgentConcurrencyUtilHooks.newFixedThreadPool(1));
        installed();
        try {
            AgentConcurrencyUtilHooks.submit(handedIn, (Runnable) () -> { });

            assertFalse(AsyncTestContext.executorShutdownDetector().analyze().hasIssues(),
                    "an executor the code was handed is not its to close");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the factory hooks return working executors of the declared types")
    void factoriesReturnWhatTheJdkReturns() throws Exception {
        ScheduledExecutorService scheduled = kept(AgentConcurrencyUtilHooks.newScheduledThreadPool(1));
        assertEquals(7, scheduled.schedule(() -> 7, 1, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS));
        ExecutorService virtual = kept(AgentConcurrencyUtilHooks.newVirtualThreadPerTaskExecutor());
        assertEquals("v", virtual.submit(() -> "v").get(5, TimeUnit.SECONDS));
        assertInstanceOf(ScheduledExecutorService.class,
                kept(AgentConcurrencyUtilHooks.newSingleThreadScheduledExecutor()));
        assertInstanceOf(ExecutorService.class,
                kept(AgentConcurrencyUtilHooks.newWorkStealingPool(1)));
    }

    // --- FUTURE_IGNORED -----------------------------------------------------------------------

    @Test
    @DisplayName("a discarded submit whose task threw is reported with the exception it swallowed")
    void discardedFailedSubmitIsReported() throws Exception {
        ExecutorService pool = kept(Executors.newSingleThreadExecutor());
        installed();
        try {
            Future<?> future = AgentConcurrencyUtilHooks.submit(pool, (Runnable) () -> {
                throw new IllegalStateException("order 42 rejected");
            });
            AgentConcurrencyUtilHooks.submitResultDiscarded(future);
            awaitDone(future);

            FutureIgnoredDetector.FutureIgnoredReport report =
                    AsyncTestContext.futureIgnoredDetector().analyze();
            assertTrue(report.hasIssues(),
                    "the caller popped the future, so nothing can ever call get() on it, and its "
                            + "task failed: the exception is gone. " + report);
            assertTrue(report.toString().contains("IllegalStateException"), report.toString());
            assertTrue(report.toString().contains("order 42 rejected"), report.toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a discarded submit whose task succeeded stays silent")
    void discardedSucceededSubmitIsSilent() throws Exception {
        ExecutorService pool = kept(Executors.newSingleThreadExecutor());
        installed();
        try {
            Future<?> future = AgentConcurrencyUtilHooks.submit(pool, (Runnable) () -> { });
            AgentConcurrencyUtilHooks.submitResultDiscarded(future);
            awaitDone(future);

            assertFalse(AsyncTestContext.futureIgnoredDetector().analyze().hasIssues(),
                    "fire-and-forget of a task that cannot fail, or did not, loses nothing; the "
                            + "agent path reports a swallowed exception, not a style");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a discarded submit still running at analysis stays silent")
    void discardedRunningSubmitIsSilent() throws Exception {
        ExecutorService pool = kept(Executors.newSingleThreadExecutor());
        CountDownLatch release = new CountDownLatch(1);
        installed();
        try {
            Future<?> future = AgentConcurrencyUtilHooks.submit(pool, (Runnable) () -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("too late to matter");
            });
            AgentConcurrencyUtilHooks.submitResultDiscarded(future);

            assertFalse(AsyncTestContext.futureIgnoredDetector().analyze().hasIssues(),
                    "a task that has not finished has swallowed nothing yet");
        } finally {
            release.countDown();
            AsyncTestContext.uninstall();
        }
    }

    private static void awaitDone(Future<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(future.isDone(), "the task did not finish");
    }
}
