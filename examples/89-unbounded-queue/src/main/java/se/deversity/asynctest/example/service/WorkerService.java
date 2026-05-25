package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes submitted work on a fixed-size thread pool.
 *
 * <p>BUG: {@code Executors.newFixedThreadPool(2)} backs the pool with an
 * unbounded {@link LinkedBlockingQueue} (capacity = {@link Integer#MAX_VALUE}).
 * Under sustained load the queue grows without any upper limit, eventually
 * causing an {@link OutOfMemoryError} before pending tasks are processed.
 */
public class WorkerService {

    // BUG: newFixedThreadPool uses an unbounded LinkedBlockingQueue internally.
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicInteger completedTasks = new AtomicInteger(0);

    /**
     * Submit a task with no backpressure. Returns immediately regardless of
     * how many tasks are already queued.
     */
    public Future<?> submit(String taskId) {
        return pool.submit(() -> {
            // Simulate some work.
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            completedTasks.incrementAndGet();
        });
    }

    public int getCompletedTasks() {
        return completedTasks.get();
    }

    public ExecutorService getPool() {
        return pool;
    }

    public void shutdown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(1, TimeUnit.SECONDS);
    }
}
