package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

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
    /**
     * Disable.
     */
    public void disable() { enabled = false; }
    /**
     * Enable.
     */
    public void enable()  { enabled = true;  }
    /**
     * Registers executor for tracking.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param name a label identifying the executor in the report
     * @param maxThreads the configured maximum thread count
     */
    public void registerExecutor(Object executor, String name, int maxThreads) {
        if (!enabled || executor == null) {
            return;
        }
        executors.putIfAbsent(System.identityHashCode(executor),
            new ExecutorState(name == null || name.isBlank() ? "Executor" : name, maxThreads));
    }
    /**
     * Records task submitted so it can be analysed at the end of the run.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskSubmitted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.submittedTasks.incrementAndGet();
        }
    }
    /**
     * Records task started so it can be analysed at the end of the run.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskStarted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.runningTasks.incrementAndGet();
        }
    }
    /**
     * Records blocking wait so it can be analysed at the end of the run.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordBlockingWait(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.blockingTasks.incrementAndGet();
        }
    }
    /**
     * Records task completed so it can be analysed at the end of the run.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskCompleted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.runningTasks.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    private @Nullable ExecutorState stateFor(Object executor) {
        if (!enabled || executor == null) {
            return null;
        }
        return executors.get(System.identityHashCode(executor));
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
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
        /** Blocking calls made from a pool thread, which can exhaust the pool. */
        public final Set<String> starvationRisks = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
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
            sb.append("""
  Why: A thread calling Future.get() or CompletableFuture.join() blocks until the task completes.
       If the task was submitted to the same bounded executor whose thread is now blocked, the
       executor has one fewer available thread. When all threads are blocked waiting for queued
       tasks, those tasks can never run — the executor is deadlocked.
  Fix:
    - Submit blocking-wait tasks to a different (unbounded or larger) executor
    - Use non-blocking composition instead: thenApply/thenCompose instead of get()/join()
    - For virtual threads: blocking inside a virtual thread is safe — there is no pool exhaustion\
""");
            return sb.toString();
        }
    }
}
