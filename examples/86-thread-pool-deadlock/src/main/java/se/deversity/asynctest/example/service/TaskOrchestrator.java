package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates multi-step tasks using a shared fixed-size thread pool.
 *
 * <p>BUG: {@link #orchestrate(String)} submits task A to the pool and, inside
 * task A, submits task B to the <em>same</em> pool and blocks on its result.
 * When all pool threads are occupied by blocking task-A calls, no thread is
 * left to start task B — causing a thread-pool deadlock.
 */
public class TaskOrchestrator {

    private final ExecutorService pool;

    public TaskOrchestrator() {
        // Pool size of 2 makes the deadlock reach quickly under concurrency.
        this.pool = Executors.newFixedThreadPool(2);
    }

    /**
     * Run a two-step orchestration where step A waits for step B.
     *
     * <p>BUG: both steps are submitted to the same pool. When the pool is full
     * of step-A threads all waiting on step-B futures, no thread can run step B.
     */
    public String orchestrate(String input) throws ExecutionException, InterruptedException {
        Future<String> resultFuture = pool.submit(() -> {
            // Step A: immediately submits step B to the same pool and blocks.
            Future<String> innerFuture = pool.submit(() -> {
                // Step B: the actual computation.
                return "processed:" + input.toUpperCase();
            });
            // BUG: blocks a pool thread while waiting for a task queued in the same pool.
            return innerFuture.get();
        });
        return resultFuture.get();
    }

    public ExecutorService getPool() {
        return pool;
    }

    public void shutdown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(1, TimeUnit.SECONDS);
    }
}
