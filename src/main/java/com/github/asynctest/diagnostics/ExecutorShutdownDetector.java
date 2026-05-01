package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Detects {@link ExecutorService} instances that are created and used but never properly
 * shut down, or shut down without a subsequent {@code awaitTermination()} call.
 *
 * <p>Forgetting to shut down an executor is one of the most common resource leaks in Java
 * concurrent code. Thread pool threads are GC-root-attached daemon or non-daemon threads
 * that never terminate unless the pool is explicitly shut down.
 *
 * <p>Issues detected:
 * <ul>
 *   <li>Executor had tasks submitted but {@code shutdown()} was never called → thread leak</li>
 *   <li>Executor was shut down but {@code awaitTermination()} was never called → submitted tasks
 *       may be silently abandoned or still running when the test finishes</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectExecutorShutdown = true)
 * void testExecutorLifecycle() {
 *     ExecutorService ex = Executors.newFixedThreadPool(2);
 *     AsyncTestContext.executorShutdownMonitor().recordExecutorCreated(ex, "my-pool");
 *     ex.submit(() -> doWork());
 *     AsyncTestContext.executorShutdownMonitor().recordTaskSubmitted(ex);
 *     // Missing: ex.shutdown() + awaitTermination → will be detected
 * }
 * }</pre>
 */
public class ExecutorShutdownDetector {

    private static class ExecutorState {
        final String name;
        final AtomicInteger tasksSubmitted = new AtomicInteger(0);
        volatile boolean shutdownCalled = false;
        volatile boolean awaitTerminationCalled = false;

        ExecutorState(String name) {
            this.name = name;
        }
    }

    private final Map<Integer, ExecutorState> executors = new ConcurrentHashMap<>();

    /**
     * Register an executor for lifecycle monitoring.
     *
     * @param executor the executor to monitor (null-safe)
     * @param name     a descriptive label for reports; if null uses identity hash
     */
    public void recordExecutorCreated(ExecutorService executor, String name) {
        if (executor == null) return;
        String resolved = name != null ? name : "executor@" + System.identityHashCode(executor);
        executors.put(System.identityHashCode(executor), new ExecutorState(resolved));
    }

    /**
     * Record a task submission ({@code submit}, {@code execute}, {@code invokeAll}, etc.).
     *
     * @param executor the executor tasks were submitted to
     */
    public void recordTaskSubmitted(ExecutorService executor) {
        if (executor == null) return;
        ExecutorState state = executors.get(System.identityHashCode(executor));
        if (state != null) state.tasksSubmitted.incrementAndGet();
    }

    /**
     * Record that {@code shutdown()} or {@code shutdownNow()} was called.
     *
     * @param executor               the executor being shut down
     * @param withAwaitTermination   {@code true} if {@code awaitTermination()} is called
     *                               immediately after in the same block (convenience shortcut)
     */
    public void recordShutdownCalled(ExecutorService executor, boolean withAwaitTermination) {
        if (executor == null) return;
        ExecutorState state = executors.get(System.identityHashCode(executor));
        if (state != null) {
            state.shutdownCalled = true;
            if (withAwaitTermination) state.awaitTerminationCalled = true;
        }
    }

    /**
     * Record that {@code awaitTermination()} was called on the executor.
     *
     * @param executor the executor being awaited
     */
    public void recordAwaitTerminationCalled(ExecutorService executor) {
        if (executor == null) return;
        ExecutorState state = executors.get(System.identityHashCode(executor));
        if (state != null) state.awaitTerminationCalled = true;
    }

    /**
     * Analyze registered executors for shutdown issues.
     *
     * @return report describing any issues found
     */
    public ExecutorShutdownReport analyze() {
        ExecutorShutdownReport report = new ExecutorShutdownReport();
        for (ExecutorState state : executors.values()) {
            if (state.tasksSubmitted.get() > 0 && !state.shutdownCalled) {
                report.notShutDown.add(String.format(
                    "%s: %d task(s) submitted but shutdown() never called — threads will leak",
                    state.name, state.tasksSubmitted.get()));
            } else if (state.shutdownCalled && !state.awaitTerminationCalled) {
                report.noAwaitTermination.add(String.format(
                    "%s: shutdown() called but awaitTermination() never called — "
                    + "in-flight tasks may be abandoned when the test ends",
                    state.name));
            }
        }
        return report;
    }

    /** Report produced by {@link #analyze()}. */
    public static class ExecutorShutdownReport {
        final List<String> notShutDown       = new ArrayList<>();
        final List<String> noAwaitTermination = new ArrayList<>();

        public boolean hasIssues() {
            return !notShutDown.isEmpty() || !noAwaitTermination.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("EXECUTOR SHUTDOWN ISSUES DETECTED:\n");
            for (String issue : notShutDown)        sb.append("  - ").append(issue).append("\n");
            for (String issue : noAwaitTermination) sb.append("  - ").append(issue).append("\n");
            sb.append("  Fix: always call shutdown() followed by awaitTermination() "
                    + "to ensure a clean executor lifecycle and prevent thread leaks");
            return sb.toString();
        }
    }
}
