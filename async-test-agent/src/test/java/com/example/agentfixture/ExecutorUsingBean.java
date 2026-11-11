package com.example.agentfixture;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Creates executors and submits to them the way application code does, recording nothing.
 *
 * <p>{@link #leakPool} makes a pool through {@code Executors}, uses it and leaves it running;
 * {@link #closePool} does the same and closes it. {@link #dropFailingSubmit} submits a task that
 * throws and discards the future as a statement; {@link #keepFailingSubmit} keeps it and reads the
 * failure. Woven with {@code collections=true}, the factory and the {@code POP} after
 * {@code submit} reach the hooks.
 *
 * <p>{@link #scheduleFailingHeartbeat} schedules a periodic task that throws on its first run,
 * which ends the schedule for good; {@link #scheduleCatchingHeartbeat} catches the same exception
 * inside the task, so it keeps running (#1254).
 */
public class ExecutorUsingBean {

    /** Pools {@link #leakPool} left running, for the test to close once it has judged them. */
    public static final Queue<ExecutorService> LEFT_RUNNING = new ConcurrentLinkedQueue<>();

    /**
     * Creates a pool, runs one task on it and leaves it running.
     *
     * @throws Exception if the task fails
     */
    public void leakPool() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        LEFT_RUNNING.add(pool);
        pool.submit(() -> { }).get();
    }

    /**
     * Creates a pool, runs one task on it, and closes it.
     *
     * @throws Exception if the task fails or the pool does not terminate
     */
    public void closePool() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> { }).get();
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the pool did not terminate");
        }
    }

    /**
     * Submits a failing task and discards its future.
     *
     * @param pool the executor to submit to
     */
    public void dropFailingSubmit(ExecutorService pool) {
        pool.submit(ExecutorUsingBean::fail);
    }

    /**
     * Submits a failing task, keeps its future and reads the failure.
     *
     * @param pool the executor to submit to
     * @throws InterruptedException if interrupted while waiting
     */
    public void keepFailingSubmit(ExecutorService pool) throws InterruptedException {
        Future<?> future = pool.submit(ExecutorUsingBean::fail);
        try {
            future.get();
        } catch (ExecutionException expected) {
            // observed, which is what keeping the future is for
        }
    }

    /**
     * Schedules a heartbeat that throws on its first run, keeps nothing but a local, and waits
     * until the executor has ended the schedule.
     *
     * @param scheduler the scheduler to run it on
     * @throws InterruptedException if interrupted while waiting
     */
    public void scheduleFailingHeartbeat(ScheduledExecutorService scheduler)
            throws InterruptedException {
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(ExecutorUsingBean::fail,
                0, 1, TimeUnit.MILLISECONDS);
        awaitEnded(heartbeat);
    }

    /**
     * Schedules a heartbeat that catches its own exception, lets it run twice, and cancels it.
     *
     * @param scheduler the scheduler to run it on
     * @throws InterruptedException if interrupted while waiting
     */
    public void scheduleCatchingHeartbeat(ScheduledExecutorService scheduler)
            throws InterruptedException {
        java.util.concurrent.CountDownLatch twice = new java.util.concurrent.CountDownLatch(2);
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(() -> {
            try {
                twice.countDown();
                fail();
            } catch (IllegalStateException logged) {
                // logged and carried on, which keeps the schedule alive
            }
        }, 0, 1, TimeUnit.MILLISECONDS);
        if (!twice.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the heartbeat stopped");
        }
        heartbeat.cancel(false);
    }

    private static void awaitEnded(Future<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the schedule did not end");
            }
            Thread.sleep(1);
        }
    }

    private static void fail() {
        throw new IllegalStateException("only the future holds this");
    }
}
