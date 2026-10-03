package se.deversity.asynctest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.SharedMessageDigestDetector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared-instance hooks see the threads a worker hands work to (#834 item 3): a thread it
 * starts through the woven {@code Thread.start}, and a task it submits to a JDK executor. Before,
 * those hooks read the context on the accessing thread only, so a spawned thread's use of a shared
 * {@code MessageDigest} was invisible, however it overlapped the worker's.
 *
 * <p>The hooks are called directly, which is what the woven call sites do. Each overlapping case
 * has an ordered twin, joined or got through the woven hook, which must stay silent.
 */
class SpawnedWorkSharedFeedTest {

    private static AsyncTestContext digestContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectSharedMessageDigest(true).build());
    }

    private static SharedMessageDigestDetector detectorOf(AsyncTestContext ctx) {
        AsyncTestContext.install(ctx);
        try {
            return AsyncTestContext.sharedMessageDigestDetector();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs {@code body} on one worker with {@code ctx} installed and rethrows what it threw. */
    private static void onAWorker(AsyncTestContext ctx, ThrowingRunnable body) throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            AsyncTestContext.install(ctx);
            try {
                body.run();
            } catch (Throwable t) { // NOPMD - handed to the test thread below
                failed.set(t);
            } finally {
                AsyncTestContext.uninstall();
            }
        }, "worker-0");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(20));
        assertNull(failed.get(), () -> "the worker failed: " + failed.get());
    }

    /** A body step that may throw. */
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The worker starts a thread through the woven start; both use the digest, at once when
     * {@code overlap}, else the worker joins the thread first through the woven join.
     */
    private static boolean startedThreadReported(boolean overlap) throws Exception {
        AsyncTestContext ctx = digestContext();
        MessageDigest md = sha256();
        CyclicBarrier together = new CyclicBarrier(2);
        onAWorker(ctx, () -> {
            Thread child = new Thread(() -> {
                if (overlap) {
                    await(together);
                }
                AgentSharedInstanceHooks.update(md, (byte) 1);
                if (overlap) {
                    await(together);
                }
            }, "spawned");
            AgentThreadHooks.threadStart(child);
            if (overlap) {
                await(together);
                AgentSharedInstanceHooks.update(md, (byte) 2);
                await(together);
            }
            AgentThreadHooks.threadJoin(child);
            if (!overlap) {
                AgentSharedInstanceHooks.update(md, (byte) 2);
            }
        });
        return detectorOf(ctx).analyze().hasIssues();
    }

    @Test
    @DisplayName("a thread the worker started is seen by the shared-instance hooks (#834)")
    void aStartedThreadsUseIsSeen() throws Exception {
        assertTrue(startedThreadReported(true),
                "the spawned thread and the worker used the digest at once, unguarded; the "
                        + "spawned thread's update must reach the detector");
        assertFalse(startedThreadReported(false),
                "the worker joined the thread before its own update, so the two are ordered");
    }

    /**
     * The worker submits a task through the woven submit; both use the digest, at once when
     * {@code overlap}, else the worker gets the task's future first through the woven get.
     */
    private static boolean submittedTaskReported(boolean overlap) throws Exception {
        AsyncTestContext ctx = digestContext();
        MessageDigest md = sha256();
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            onAWorker(ctx, () -> {
                Runnable task = () -> {
                    if (overlap) {
                        await(together);
                    }
                    AgentSharedInstanceHooks.update(md, (byte) 1);
                    if (overlap) {
                        await(together);
                    }
                };
                @SuppressWarnings("unchecked")
                Future<Object> future = (Future<Object>) AgentConcurrencyUtilHooks.submit(executor, task);
                if (overlap) {
                    await(together);
                    AgentSharedInstanceHooks.update(md, (byte) 2);
                    await(together);
                }
                AgentConcurrencyUtilHooks.get(future);
                if (!overlap) {
                    AgentSharedInstanceHooks.update(md, (byte) 2);
                }
            });
        } finally {
            executor.shutdownNow();
        }
        return detectorOf(ctx).analyze().hasIssues();
    }

    @Test
    @DisplayName("a task the worker submitted is seen by the shared-instance hooks while it runs (#834)")
    void aSubmittedTasksUseIsSeen() throws Exception {
        assertTrue(submittedTaskReported(true),
                "the pool thread ran the worker's task and used the digest while the worker did; "
                        + "the task's update must reach the detector");
        assertFalse(submittedTaskReported(false),
                "the worker got the future before its own update, so the two are ordered");
    }

    @Test
    @DisplayName("a pool thread is lent the run only while it runs the handed task")
    void theLoanEndsWithTheTask() throws Exception {
        AsyncTestContext ctx = digestContext();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<AsyncTestContext> during = new AtomicReference<>();
            onAWorker(ctx, () -> AgentConcurrencyUtilHooks.get(asObjectFuture(
                    AgentConcurrencyUtilHooks.submit(executor,
                            () -> during.set(AsyncTestContext.agentContext())))));
            AsyncTestContext after = executor.submit(AsyncTestContext::agentContext).get(10, TimeUnit.SECONDS);

            assertTrue(during.get() == ctx, "the handed task ran for the worker's run");
            assertNull(after, "the next task on the same pool thread was handed over by nobody, "
                    + "so the loan must have ended in the first task's finally");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a run whose analysis has started lends nothing to the threads its threads started")
    void analysisEndsTheRunsLoans() throws Exception {
        AsyncTestContext ctx = digestContext();
        java.util.concurrent.CountDownLatch analysed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch looked = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<AsyncTestContext> before = new AtomicReference<>();
        AtomicReference<AsyncTestContext> afterwards = new AtomicReference<>();
        AtomicReference<Thread> child = new AtomicReference<>();
        onAWorker(ctx, () -> {
            Thread spawned = new Thread(() -> {
                before.set(AsyncTestContext.agentContext());
                looked.countDown();
                try {
                    analysed.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                afterwards.set(AsyncTestContext.agentContext());
            }, "outlives-its-run");
            AgentThreadHooks.threadStart(spawned);
            child.set(spawned);
        });
        assertTrue(looked.await(10, TimeUnit.SECONDS), "the started thread never ran");
        ctx.analyzeAll();
        analysed.countDown();
        child.get().join(TimeUnit.SECONDS.toMillis(10));

        assertTrue(before.get() == ctx, "the started thread worked for the run while it ran");
        assertNull(afterwards.get(), "the run was analysed, so a thread that outlived it feeds it "
                + "nothing more");
    }

    @Test
    @DisplayName("a started thread that waits past the run is not the run's latch misuse")
    void aStartedThreadsAwaitStaysWithItsOwnDetectors() throws Exception {
        AsyncTestContext ctx = new AsyncTestContext(AsyncTestConfig.builder()
                .detectSharedMessageDigest(true).detectLatchMisuse(true).build());
        java.util.concurrent.CountDownLatch laneOver = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch waiting = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Thread> child = new AtomicReference<>();
        onAWorker(ctx, () -> {
            Thread lingering = new Thread(() -> {
                waiting.countDown();
                try {
                    AgentConcurrencyUtilHooks.await(laneOver, 10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "waits-past-the-run");
            AgentThreadHooks.threadStart(lingering);
            child.set(lingering);
        });
        assertTrue(waiting.await(10, TimeUnit.SECONDS), "the started thread never ran");
        Thread.sleep(50); // let it enter the await, which is what the run would otherwise judge
        String latchReport;
        AsyncTestContext.install(ctx);
        try {
            latchReport = AsyncTestContext.latchMisuseDetector().analyze().toString();
        } finally {
            AsyncTestContext.uninstall();
        }
        laneOver.countDown();
        child.get().join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(latchReport.contains("awaited"),
                "the started thread waits on a latch counted down after the run; only the "
                        + "shared-instance feeds follow it, so the run's latch detector must not "
                        + "read its await as a gate that never opened: " + latchReport);
    }

    @SuppressWarnings("unchecked")
    private static Future<Object> asObjectFuture(Future<?> future) {
        return (Future<Object>) future;
    }

    @Test
    @DisplayName("a thread started outside any run feeds no run")
    void aThreadStartedOutsideARunFeedsNothing() throws Exception {
        AsyncTestContext ctx = digestContext();
        MessageDigest md = sha256();
        Thread stranger = new Thread(() -> AgentSharedInstanceHooks.update(md, (byte) 1), "stranger");
        AgentThreadHooks.threadStart(stranger);
        stranger.join();
        onAWorker(ctx, () -> AgentSharedInstanceHooks.update(md, (byte) 2));

        assertFalse(detectorOf(ctx).analyze().hasIssues(),
                "the stranger was started by the test thread, which runs no context, so only the "
                        + "worker's update belongs to the run");
    }
}
