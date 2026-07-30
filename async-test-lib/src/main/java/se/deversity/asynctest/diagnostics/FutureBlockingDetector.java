package se.deversity.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects blocking waits on sibling futures inside bounded executors.
 *
 * <p>Reachable from a test via {@code AsyncTestContext.futureBlockingDetector()} when
 * {@link se.deversity.asynctest.DetectorType#FUTURE_BLOCKING} is enabled.
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

    public void disable() { enabled = false; }
    public void enable()  { enabled = true;  }

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
            sb.append("  Why: A thread calling Future.get() or CompletableFuture.join() blocks until the task completes.\n" +
                       "       If the task was submitted to the same bounded executor whose thread is now blocked, the\n" +
                       "       executor has one fewer available thread. When all threads are blocked waiting for queued\n" +
                       "       tasks, those tasks can never run — the executor is deadlocked.\n" +
                       "  Fix:\n" +
                       "    - Submit blocking-wait tasks to a different (unbounded or larger) executor\n" +
                       "    - Use non-blocking composition instead: thenApply/thenCompose instead of get()/join()\n" +
                       "    - For virtual threads: blocking inside a virtual thread is safe — there is no pool exhaustion");
            return sb.toString();
        }
    }
}
