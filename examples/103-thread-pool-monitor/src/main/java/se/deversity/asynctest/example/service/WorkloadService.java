package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Submits work to a fixed-size thread pool.
 *
 * <p><strong>Bug:</strong> The pool has only 2 threads. Under concurrent load from
 * many callers, the pool queue depth grows rapidly: all 2 threads are always busy and
 * every new submission must wait. This is a classic thread-pool bottleneck — the pool
 * is undersized relative to the workload it receives.
 *
 * <p><strong>Fix:</strong> Size the pool based on expected concurrency:
 * {@code Runtime.getRuntime().availableProcessors()} for CPU-bound work, or use
 * {@link Executors#newVirtualThreadPerTaskExecutor()} for I/O-bound work.
 */
public class WorkloadService {

    // BUG: only 2 threads — becomes a bottleneck under high concurrent load
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    /**
     * Submits a unit of work to the pool.
     *
     * @param task the work to execute
     * @return a {@link Future} representing the pending result
     */
    public Future<?> submitWork(Runnable task) {
        return pool.submit(task);
    }

    /**
     * Returns the number of threads actively executing tasks.
     */
    public int getActiveCount() {
        return ((ThreadPoolExecutor) pool).getActiveCount();
    }

    /**
     * Returns the current number of tasks sitting in the queue.
     */
    public int getQueueSize() {
        return ((ThreadPoolExecutor) pool).getQueue().size();
    }

    /**
     * Returns the underlying executor for test instrumentation.
     */
    public ExecutorService getPool() {
        return pool;
    }

    /**
     * Shuts down the pool (call in test teardown).
     */
    public void shutdown() {
        pool.shutdownNow();
    }
}
