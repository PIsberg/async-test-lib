package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs submitted tasks on a fixed thread pool.
 *
 * <p><strong>Bug:</strong> The {@link ExecutorService} is created in the constructor
 * but {@code shutdown()} is never called. Each instance leaks four threads for the
 * lifetime of the JVM. Under repeated concurrent test invocations the thread count
 * grows without bound.
 *
 * <p><strong>Fix:</strong> Implement {@link AutoCloseable} and call
 * {@code executor.shutdown()} followed by {@code executor.awaitTermination()},
 * or use a try-with-resources block around the service.
 */
public class TaskRunnerService {

    // BUG: executor is never shut down
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * Submits a task to the internal thread pool.
     *
     * @param task the runnable to execute
     * @return a {@link Future} representing the pending result
     */
    public Future<?> runTask(Runnable task) {
        return executor.submit(task);
    }

    /** Returns the underlying executor for instrumentation in tests. */
    public ExecutorService getExecutor() {
        return executor;
    }
}
