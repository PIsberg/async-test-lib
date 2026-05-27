package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * BUGGY service that demonstrates missing custom ThreadFactory.
 *
 * BUG: the pool is created with Executors.newFixedThreadPool(4) and no
 *      ThreadFactory. Default threads have:
 *      - Generic names (pool-N-thread-M) — useless in thread dumps.
 *      - Non-daemon status — JVM hangs on exit if shutdown() is not called.
 *      - No UncaughtExceptionHandler — errors may be silently swallowed.
 *
 * FIX: supply a ThreadFactory that assigns meaningful names, sets daemon=true,
 *      and installs an UncaughtExceptionHandler:
 *
 * <pre>{@code
 * AtomicInteger n = new AtomicInteger();
 * ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
 *     Thread t = new Thread(r, "worker-" + n.incrementAndGet());
 *     t.setDaemon(true);
 *     t.setUncaughtExceptionHandler((th, ex) -> LOG.error("Uncaught", ex));
 *     return t;
 * });
 * }</pre>
 */
public class WorkerPoolService {

    // BUG: no ThreadFactory — default factory produces unnamed, non-daemon threads.
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    /**
     * Submit a task to the worker pool.
     *
     * @param task the work to run
     * @return future representing completion
     */
    public Future<?> submit(Runnable task) {
        return pool.submit(task);
    }

    /**
     * Shut down the pool.
     * Without daemon threads the JVM will block here until all tasks finish.
     */
    public void shutdown() {
        pool.shutdown();
    }

    public ExecutorService getPool() {
        return pool;
    }
}
