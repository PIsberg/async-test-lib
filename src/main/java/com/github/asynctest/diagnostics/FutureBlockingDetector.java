package com.github.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects blocking waits on sibling futures inside bounded executors.
 */
public class FutureBlockingDetector {

    private static class ExecutorState {
        final String name;
        final int maxThreads;
        final AtomicInteger submittedTasks = new AtomicInteger();
        final AtomicInteger runningTasks = new AtomicInteger();
        final AtomicInteger blockingTasks = new AtomicInteger();

        ExecutorState(String name, int maxThreads) {
            this.name = name;
            this.maxThreads = maxThreads;
        }
    }

    private final Map<Integer, ExecutorState> executors = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    public void registerExecutor(Object executor, String name, int maxThreads) {
        if (!enabled || executor == null) {
            return;
        }
        executors.putIfAbsent(System.identityHashCode(executor),
            new ExecutorState(name == null || name.isBlank() ? "Executor" : name, maxThreads));
    }

    public void recordTaskSubmitted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.submittedTasks.incrementAndGet();
        }
    }

    public void recordTaskStarted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.runningTasks.incrementAndGet();
        }
    }

    public void recordBlockingWait(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.blockingTasks.incrementAndGet();
        }
    }

    public void recordTaskCompleted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.runningTasks.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    private ExecutorState stateFor(Object executor) {
        if (!enabled || executor == null) {
            return null;
        }
        return executors.get(System.identityHashCode(executor));
    }

    public FutureBlockingReport analyze() {
        FutureBlockingReport report = new FutureBlockingReport();

        for (ExecutorState state : executors.values()) {
            int queued = Math.max(0, state.submittedTasks.get() - state.runningTasks.get());
            if (state.blockingTasks.get() >= state.maxThreads && queued > 0) {
                report.starvationRisks.add(String.format(
                    "%s: %d/%d workers blocked waiting on futures while %d task(s) remain queued",
                    state.name,
                    state.blockingTasks.get(),
                    state.maxThreads,
                    queued
                ));
            }
        }

        return report;
    }

    public static class FutureBlockingReport {
        public final Set<String> starvationRisks = new HashSet<>();

        public boolean hasIssues() {
            return !starvationRisks.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No future blocking starvation detected.";
            }

            StringBuilder sb = new StringBuilder("FUTURE BLOCKING ISSUES DETECTED:\n");
            for (String issue : starvationRisks) {
                sb.append("  - ").append(issue).append('\n');
            }
            sb.append("  Fix: avoid blocking get()/join() on tasks scheduled to the same bounded executor");
            return sb.toString();
        }
    }
}
