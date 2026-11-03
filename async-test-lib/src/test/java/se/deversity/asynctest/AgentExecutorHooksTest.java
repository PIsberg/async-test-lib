package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ExecutorShutdownDetector;
import se.deversity.asynctest.diagnostics.FutureIgnoredDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    // --- A task waiting on its own pool (#1160) ------------------------------------------------

    /**
     * Runs a task on {@code outer} that submits to {@code inner} and waits for it, briefly, through
     * the woven calls, and {@return what the run's detector reports}.
     */
    private boolean taskWaitingOnAPoolIsReported(ExecutorService outer, ExecutorService inner)
            throws Exception {
        AsyncTestContext context = installed();
        try {
            Future<Object> task = AgentConcurrencyUtilHooks.submit(outer, (Callable<Object>) () -> {
                Future<Object> nested = AgentConcurrencyUtilHooks.submit(inner, (Callable<Object>) () -> "done");
                try {
                    return AgentConcurrencyUtilHooks.get(nested, 100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException expected) {
                    return "timed out";
                }
            });
            AgentConcurrencyUtilHooks.get(task);
            return context.executorDeadlockDetector.analyze().hasIssues();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a task waiting on its own single-thread pool is reported")
    void aTaskWaitingOnItsOwnSingleThreadPoolIsReported() throws Exception {
        ExecutorService pool = kept(Executors.newSingleThreadExecutor());
        assertTrue(taskWaitingOnAPoolIsReported(pool, pool),
                "the nested task can only run on the thread that is waiting for it");
    }

    @Test
    @DisplayName("a task waiting on its own one-thread fixed pool is reported")
    void aTaskWaitingOnItsOwnFixedPoolIsReported() throws Exception {
        ExecutorService pool = kept(Executors.newFixedThreadPool(1));
        assertTrue(taskWaitingOnAPoolIsReported(pool, pool),
                "the same deadlock through the class newFixedThreadPool builds");
    }

    @Test
    @DisplayName("a task waiting on another pool is not reported")
    void aTaskWaitingOnAnotherPoolIsNotReported() throws Exception {
        assertFalse(taskWaitingOnAPoolIsReported(kept(Executors.newSingleThreadExecutor()),
                        kept(Executors.newSingleThreadExecutor())),
                "the nested task has a thread of its own");
    }

    @Test
    @DisplayName("a task waiting on its own pool with a thread to spare is not reported")
    void aTaskWaitingOnAPoolWithAFreeThreadIsNotReported() throws Exception {
        ExecutorService pool = kept(Executors.newFixedThreadPool(2));
        assertFalse(taskWaitingOnAPoolIsReported(pool, pool), "the second thread runs the nested task");
    }

    @Test
    @DisplayName("a task waiting on its own growing pool is not reported")
    void aTaskWaitingOnACachedPoolIsNotReported() throws Exception {
        ExecutorService pool = kept(Executors.newCachedThreadPool());
        assertFalse(taskWaitingOnAPoolIsReported(pool, pool), "a cached pool starts another thread");
    }

    // --- A ThreadPoolExecutor's execute inside a run (#1155) -----------------------------------

    /** A one-thread pool whose thread is held until {@code release} counts down. */
    private ThreadPoolExecutor heldPool(CountDownLatch release) {
        ThreadPoolExecutor pool = kept(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()));
        pool.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return pool;
    }

    @Test
    @DisplayName("shutdownNow gives back the task a woven execute was given, not its wrapper")
    void shutdownNowGivesBackTheTaskItself() {
        installed();
        CountDownLatch release = new CountDownLatch(1);
        try {
            ThreadPoolExecutor pool = heldPool(release);
            Runnable queued = () -> { };
            AgentConcurrencyUtilHooks.execute(pool, queued);

            assertEquals(List.of(queued), AgentConcurrencyUtilHooks.shutdownNow(pool),
                    "code that re-queues or inspects what shutdownNow returned sees its own task");
        } finally {
            release.countDown();
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("remove finds the task a woven execute was given, not only its wrapper")
    void removeFindsTheTaskItself() {
        installed();
        CountDownLatch release = new CountDownLatch(1);
        try {
            ThreadPoolExecutor pool = heldPool(release);
            Runnable queued = () -> { };
            AgentConcurrencyUtilHooks.execute(pool, queued);

            assertTrue(AgentConcurrencyUtilHooks.removeFromPool(pool, queued),
                    "the task is still queued, so remove must find it");
            assertTrue(pool.getQueue().isEmpty(), "and the queue must really have let it go");
            assertFalse(AgentConcurrencyUtilHooks.removeFromPool(pool, queued),
                    "a second remove has nothing left to find");
        } finally {
            release.countDown();
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("outside a run a ThreadPoolExecutor's task is not wrapped at all")
    void outsideARunTheTaskIsLeftAlone() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            ThreadPoolExecutor pool = heldPool(release);
            Runnable queued = () -> { };
            AgentConcurrencyUtilHooks.execute(pool, queued);

            assertTrue(pool.getQueue().contains(queued),
                    "no run is watching, so nothing here should change what the pool holds");
        } finally {
            release.countDown();
        }
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
