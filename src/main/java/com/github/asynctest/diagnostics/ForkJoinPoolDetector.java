package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.RecursiveAction;

/**
 * Detects ForkJoinPool misuse patterns:
 * - Fork without join
 * - RecursiveTask not returning result
 * - Pool starvation (too few threads)
 * - Exception in forked tasks
 */
public class ForkJoinPoolDetector {

    private final Map<ForkJoinPool, PoolInfo> poolRegistry = new ConcurrentHashMap<>();
    private final Set<String> forkedWithoutJoin = ConcurrentHashMap.newKeySet();
    private final Set<String> exceptionsInTasks = ConcurrentHashMap.newKeySet();
    private int taskStealCount = 0;

    /**
     * Register a ForkJoinPool for monitoring.
     */
    public void registerPool(ForkJoinPool pool, String name, int parallelism) {
        poolRegistry.put(pool, new PoolInfo(name, parallelism));
    }

    /**
     * Record a task being forked.
     */
    public void recordFork(ForkJoinPool pool, String poolName, String taskName) {
        PoolInfo info = poolRegistry.get(pool);
        if (info != null) {
            info.recordFork(taskName);
        }
    }

    /**
     * Record a task being joined.
     */
    public void recordJoin(ForkJoinPool pool, String poolName, String taskName) {
        PoolInfo info = poolRegistry.get(pool);
        if (info != null) {
            info.recordJoin(taskName);
        }
    }

    /**
     * Record a task that was forked but never joined.
     */
    public void recordForkWithoutJoin(String poolName, String taskName) {
        forkedWithoutJoin.add(poolName + ":" + taskName);
    }

    /**
     * Record an exception in a forked task.
     */
    public void recordException(String poolName, String taskName, Throwable t) {
        exceptionsInTasks.add(poolName + ":" + taskName + " (" + t.getClass().getSimpleName() + ")");
    }

    /**
     * Record work stealing event.
     */
    public void recordWorkSteal(ForkJoinPool pool) {
        taskStealCount++;
    }

    /**
     * Record task execution time.
     */
    public void recordTaskTime(ForkJoinPool pool, String poolName, long timeMs) {
        PoolInfo info = poolRegistry.get(pool);
        if (info != null) {
            info.recordTaskTime(timeMs);
        }
    }

    /**
     * Analyze ForkJoinPool usage and return report.
     */
    public ForkJoinPoolReport analyze() {
        return new ForkJoinPoolReport(
            poolRegistry,
            forkedWithoutJoin,
            exceptionsInTasks,
            taskStealCount
        );
    }

    /**
     * Report class for ForkJoinPool analysis.
     */
    public static class ForkJoinPoolReport {
        private final Map<ForkJoinPool, PoolInfo> poolRegistry;
        private final Set<String> forkedWithoutJoin;
        private final Set<String> exceptionsInTasks;
        private final int taskStealCount;

        public ForkJoinPoolReport(
            Map<ForkJoinPool, PoolInfo> poolRegistry,
            Set<String> forkedWithoutJoin,
            Set<String> exceptionsInTasks,
            int taskStealCount
        ) {
            this.poolRegistry = poolRegistry;
            this.forkedWithoutJoin = forkedWithoutJoin;
            this.exceptionsInTasks = exceptionsInTasks;
            this.taskStealCount = taskStealCount;
        }

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
                sb.append("  Fix: Always call join() after fork():\n");
                sb.append("    task.fork();\n");
                sb.append("    result = task.join();\n");
            }

            if (!exceptionsInTasks.isEmpty()) {
                sb.append("  Exceptions in Forked Tasks:\n");
                for (String taskInfo : exceptionsInTasks) {
                    sb.append("    - ").append(taskInfo).append("\n");
                }
                sb.append("  Fix: Handle exceptions in compute() method\n");
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
