package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects ForkJoinPool misuse patterns:
 * <ul>
 *   <li><strong>Fork without join</strong> — reported when the caller says so via
 *       {@code recordForkWithoutJoin}. It is not inferred: {@code recordFork} and
 *       {@code recordJoin} accumulate per-pool counts that the analysis never compares.</li>
 *   <li><strong>Exception in forked tasks</strong> — from {@link #recordException(String, String, Throwable)}.</li>
 *   <li><strong>Work stealing</strong> — counted and shown, informational.</li>
 * </ul>
 *
 * <p>This list previously also claimed "RecursiveTask not returning result" and "Pool starvation
 * (too few threads)". Neither has a code path — nothing in the analysis reads anything that could
 * produce them — so a user who instrumented for those got silent green with no way to tell "not
 * detected" from "not implemented". Documenting only what is actually derived is the honest
 * surface. Deriving the fork/join imbalance from the counts already collected is the obvious
 * next step and is deliberately not done here: a test that ends mid-computation would show an
 * imbalance without a defect, and this library cannot afford that kind of finding.
 */
public class ForkJoinPoolDetector {

    private final Map<ForkJoinPool, PoolInfo> poolRegistry = new ConcurrentHashMap<>();
    private final Set<String> forkedWithoutJoin = ConcurrentHashMap.newKeySet();
    private final Set<String> exceptionsInTasks = ConcurrentHashMap.newKeySet();
    // Incremented from pool worker threads, so a plain int was a lost-update race.
    private final AtomicInteger taskStealCount = new AtomicInteger();

    /**
     * Register a ForkJoinPool for monitoring.
     *
     * @param pool the pool being recorded, tracked by identity
     * @param name a label identifying the pool in the report
     * @param parallelism the configured parallelism of the pool
     */
    public void registerPool(ForkJoinPool pool, String name, int parallelism) {
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        poolRegistry.putIfAbsent(pool, new PoolInfo(name, parallelism));
    }

    /**
     * Record a task being forked.
     *
     * @param pool the pool being recorded, tracked by identity
     * @param poolName a label identifying the pool in the report
     * @param taskName a label identifying the task in the report
     */
    public void recordFork(ForkJoinPool pool, String poolName, String taskName) {
        PoolInfo info = poolRegistry.get(pool);
        if (info != null) {
            info.recordFork(taskName);
        }
    }

    /**
     * Record a task being joined.
     *
     * @param pool the pool being recorded, tracked by identity
     * @param poolName a label identifying the pool in the report
     * @param taskName a label identifying the task in the report
     */
    public void recordJoin(ForkJoinPool pool, String poolName, String taskName) {
        PoolInfo info = poolRegistry.get(pool);
        if (info != null) {
            info.recordJoin(taskName);
        }
    }

    /**
     * Record a task that was forked but never joined.
     *
     * @param poolName a label identifying the pool in the report
     * @param taskName a label identifying the task in the report
     */
    public void recordForkWithoutJoin(String poolName, String taskName) {
        forkedWithoutJoin.add(poolName + ":" + taskName);
    }

    /**
     * Record an exception in a forked task.
     *
     * @param poolName a label identifying the pool in the report
     * @param taskName a label identifying the task in the report
     * @param t the throwable the task failed with
     */
    public void recordException(String poolName, String taskName, Throwable t) {
        exceptionsInTasks.add(poolName + ":" + taskName + " (" + t.getClass().getSimpleName() + ")");
    }

    /**
     * Record work stealing event.
     *
     * @param pool the pool being recorded, tracked by identity
     */
    public void recordWorkSteal(ForkJoinPool pool) {
        taskStealCount.incrementAndGet();
    }

    /**
     * Record task execution time.
     *
     * @param pool the pool being recorded, tracked by identity
     * @param poolName a label identifying the pool in the report
     * @param timeMs the time in milliseconds
     */
    public void recordTaskTime(ForkJoinPool pool, String poolName, long timeMs) {
        PoolInfo info = poolRegistry.get(pool);
        if (info != null) {
            info.recordTaskTime(timeMs);
        }
    }

    /**
     * Analyze ForkJoinPool usage and return report.
     *
     * @return the findings this detector collected during the run
     */
    public ForkJoinPoolReport analyze() {
        return new ForkJoinPoolReport(
            forkedWithoutJoin,
            exceptionsInTasks,
            taskStealCount.get()
        );
    }

    /**
     * Report class for ForkJoinPool analysis.
     */
    public static class ForkJoinPoolReport {
        private final Set<String> forkedWithoutJoin;
        private final Set<String> exceptionsInTasks;
        private final int taskStealCount;
        /**
         * Creates a ForkJoinPoolReport.
         *
         * @param forkedWithoutJoin the tasks forked but never joined
         * @param exceptionsInTasks the exceptions thrown inside pool tasks
         * @param taskStealCount how many tasks were stolen between workers
         */
        public ForkJoinPoolReport(
            Set<String> forkedWithoutJoin,
            Set<String> exceptionsInTasks,
            int taskStealCount
        ) {
            this.forkedWithoutJoin = Collections.unmodifiableSet(new HashSet<>(forkedWithoutJoin));
            this.exceptionsInTasks = Collections.unmodifiableSet(new HashSet<>(exceptionsInTasks));
            this.taskStealCount = taskStealCount;
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !forkedWithoutJoin.isEmpty() 
                || !exceptionsInTasks.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("FORKJOINPOOL ISSUES DETECTED:\n");

            if (!forkedWithoutJoin.isEmpty()) {
                sb.append("  Tasks Forked But Not Joined:\n");
                for (String taskInfo : forkedWithoutJoin) {
                    sb.append("    - ").append(taskInfo).append("\n");
                }
                sb.append("  Problem: Forked tasks must be joined to get results and exceptions\n");
                sb.append("""
  Why: fork() submits the task asynchronously but does not wait for it. Without join(), the task result
       is discarded and any exception it throws is silently lost. The parent task proceeds with a missing
       or default value, producing silently wrong computation results.
""");
                sb.append("  Fix: Always call join() after fork() to retrieve the result and propagate exceptions:\n");
                sb.append("    task.fork(); result = task.join();  // or: result = task.invoke() (fork + join in one call)\n");
            }

            if (!exceptionsInTasks.isEmpty()) {
                sb.append("  Exceptions in Forked Tasks:\n");
                for (String taskInfo : exceptionsInTasks) {
                    sb.append("    - ").append(taskInfo).append("\n");
                }
                sb.append("""
  Why: An uncaught exception in compute() propagates to the join() caller as an unchecked RuntimeException.
       If join() is never called (leaked fork), the exception is silently dropped.
""");
                sb.append("  Fix: Wrap the body of compute() in try/catch and either handle the exception or rethrow as RuntimeException\n");
            }

            if (taskStealCount > 0) {
                sb.append("  Work Stealing Events: ").append(taskStealCount).append("\n");
                sb.append("  Note: Work stealing is normal FJP behavior for load balancing\n");
            }

            if (!hasIssues()) {
                sb.append("  No ForkJoinPool issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal pool information.
     */
    static class PoolInfo {
        final String name;
        final int parallelism;
        int forkCount = 0;
        int joinCount = 0;
        long totalTaskTime = 0;
        int taskCount = 0;

        PoolInfo(String name, int parallelism) {
            this.name = name;
            this.parallelism = parallelism;
        }

        synchronized void recordFork(String taskName) {
            forkCount++;
        }

        synchronized void recordJoin(String taskName) {
            joinCount++;
        }

        synchronized void recordTaskTime(long timeMs) {
            totalTaskTime += timeMs;
            taskCount++;
        }
    }
}
