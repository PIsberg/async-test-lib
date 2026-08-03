package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects self-deadlock patterns in single-thread or bounded executors.
 *
 * <p>Reachable from a test via {@code AsyncTestContext.executorDeadlockDetector()} when
 * {@link se.deversity.asynctest.DetectorType#EXECUTOR_DEADLOCK} is enabled.
 */
public class ExecutorDeadlockDetector {

    private static class ExecutorState {
        final String name;
        final int maxThreads;
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger waitingOnSibling = new AtomicInteger();

        ExecutorState(String name, int maxThreads) {
            this.name = name;
            this.maxThreads = maxThreads;
        }
    }

    private final Map<Integer, ExecutorState> executors = new ConcurrentHashMap<>();
    /**
     * Registers executor for tracking.
     *
     * @param executor the executor
     * @param name the name
     * @param maxThreads the max threads
     */

    public void registerExecutor(Object executor, String name, int maxThreads) {
        if (executor == null) {
            return;
        }
        executors.putIfAbsent(System.identityHashCode(executor),
            new ExecutorState(name == null || name.isBlank() ? "Executor" : name, maxThreads));
    }
    /**
     * Records task submitted so it can be analysed at the end of the run.
     *
     * @param executor the executor
     */

    public void recordTaskSubmitted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.submitted.incrementAndGet();
        }
    }
    /**
     * Records task started so it can be analysed at the end of the run.
     *
     * @param executor the executor
     */

    public void recordTaskStarted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.running.incrementAndGet();
        }
    }
    /**
     * Records waiting on sibling so it can be analysed at the end of the run.
     *
     * @param executor the executor
     */

    public void recordWaitingOnSibling(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.waitingOnSibling.incrementAndGet();
        }
    }
    /**
     * Records task completed so it can be analysed at the end of the run.
     *
     * @param executor the executor
     */

    public void recordTaskCompleted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.running.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    private @Nullable ExecutorState stateFor(Object executor) {
        return executor == null ? null : executors.get(System.identityHashCode(executor));
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the analyze
     */

    public ExecutorDeadlockReport analyze() {
        ExecutorDeadlockReport report = new ExecutorDeadlockReport();

        for (ExecutorState state : executors.values()) {
            int queued = Math.max(0, state.submitted.get() - state.running.get());
            if (state.waitingOnSibling.get() >= state.maxThreads && queued > 0) {
                report.selfDeadlocks.add(String.format(
                    "%s: all %d worker(s) are waiting on sibling tasks while %d task(s) remain queued",
                    state.name,
                    state.maxThreads,
                    queued
                ));
            }
        }

        return report;
    }

    public static class ExecutorDeadlockReport {
        /** The self deadlocks. */
        public final Set<String> selfDeadlocks = new HashSet<>();

        /** {@return whether there are issues} */
        public boolean hasIssues() {
            return !selfDeadlocks.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No executor self-deadlocks detected.";
            }

            StringBuilder sb = new StringBuilder("EXECUTOR SELF-DEADLOCK DETECTED:\n");
            for (String issue : selfDeadlocks) {
                sb.append("  - ").append(issue).append('\n');
            }
            sb.append("  Fix: do not wait on sibling tasks from the same bounded executor");
            return sb.toString();
        }
    }
}
