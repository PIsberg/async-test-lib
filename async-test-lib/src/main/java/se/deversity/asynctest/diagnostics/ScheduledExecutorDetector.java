package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Detects ScheduledExecutorService misuse patterns:
 * - Task scheduling without proper shutdown
 * - Fixed delay vs fixed rate confusion
 * - Long-running tasks blocking scheduler
 * - Exception handling in scheduled tasks
 */
public class ScheduledExecutorDetector {

    private final Map<ScheduledExecutorService, ExecutorInfo> executorRegistry = new ConcurrentHashMap<>();
    private final Set<ScheduledExecutorService> notShutdownExecutors = ConcurrentHashMap.newKeySet();
    private final Set<String> longRunningTasks = ConcurrentHashMap.newKeySet();
    private int exceptionInTasks = 0;

    /**
     * Register a ScheduledExecutorService for monitoring.
     */
    public void registerExecutor(ScheduledExecutorService executor, String name, int corePoolSize) {
        executorRegistry.put(executor, new ExecutorInfo(name, corePoolSize));
    }

    /**
     * Record a task being scheduled.
     */
    public void recordSchedule(ScheduledExecutorService executor, String executorName, String taskName) {
        ExecutorInfo info = executorRegistry.get(executor);
        if (info != null) {
            info.recordSchedule(taskName);
        }
    }

    /**
     * Record a task starting execution.
     */
    public void recordTaskStart(ScheduledExecutorService executor, String executorName, String taskName) {
        ExecutorInfo info = executorRegistry.get(executor);
        if (info != null) {
            info.recordTaskStart(taskName);
        }
    }

    /**
     * Record a task completing execution.
     */
    public void recordTaskComplete(ScheduledExecutorService executor, String executorName, String taskName, long durationMs) {
        ExecutorInfo info = executorRegistry.get(executor);
        if (info != null) {
            info.recordTaskComplete(taskName, durationMs);
            if (durationMs > 1000) {  // More than 1 second
                longRunningTasks.add(executorName + ":" + taskName + " (" + durationMs + "ms)");
            }
        }
    }

    /**
     * Record an exception in a scheduled task.
     */
    public void recordException(ScheduledExecutorService executor, String executorName) {
        exceptionInTasks++;
    }

    /**
     * Record executor shutdown.
     */
    public void recordShutdown(ScheduledExecutorService executor) {
        ExecutorInfo info = executorRegistry.get(executor);
        if (info != null) {
            info.shutdown = true;
        }
    }

    /**
     * Check for executors not shut down at end of test.
     */
    public void checkShutdown() {
        for (Map.Entry<ScheduledExecutorService, ExecutorInfo> entry : executorRegistry.entrySet()) {
            if (!entry.getValue().shutdown) {
                notShutdownExecutors.add(entry.getKey());
            }
        }
    }

    /**
     * Analyze ScheduledExecutorService usage and return report.
     */
    public ScheduledExecutorReport analyze() {
        checkShutdown();
        return new ScheduledExecutorReport(
            executorRegistry,
            notShutdownExecutors,
            longRunningTasks,
            exceptionInTasks
        );
    }

    /**
     * Report class for ScheduledExecutorService analysis.
     */
    public static class ScheduledExecutorReport {
        private final Map<ScheduledExecutorService, ExecutorInfo> executorRegistry;
        private final Set<ScheduledExecutorService> notShutdownExecutors;
        private final Set<String> longRunningTasks;
        private final int exceptionInTasks;

        public ScheduledExecutorReport(
            Map<ScheduledExecutorService, ExecutorInfo> executorRegistry,
            Set<ScheduledExecutorService> notShutdownExecutors,
            Set<String> longRunningTasks,
            int exceptionInTasks
        ) {
            this.executorRegistry = Collections.unmodifiableMap(new HashMap<>(executorRegistry));
            this.notShutdownExecutors = Collections.unmodifiableSet(new HashSet<>(notShutdownExecutors));
            this.longRunningTasks = Collections.unmodifiableSet(new HashSet<>(longRunningTasks));
            this.exceptionInTasks = exceptionInTasks;
        }

        public boolean hasIssues() {
            return !notShutdownExecutors.isEmpty() 
                || !longRunningTasks.isEmpty()
                || exceptionInTasks > 0;
        }

        /**
         * Registry lookup that always yields a non-null {@code ExecutorInfo}.
         *
         * <p>Nothing requires a {@code record*} call's subject to have been passed to the matching
         * {@code register*} first — no precondition, no runtime check — and the two are written at
         * different places in a test. When the registration is missed the lookup returns
         * {@code null} and dereferencing it threw out of {@code toString()}. That NPE never reached
         * the user: {@code DetectorRegistry.ifIssue} catches it so one detector cannot discard the
         * whole sweep, so the finding was simply dropped and the report the user needed never
         * appeared. A placeholder keeps the finding and says plainly which subject was not
         * registered.
         */
        private ExecutorInfo infoFor(ScheduledExecutorService executor) {
            ExecutorInfo info = executorRegistry.get(executor);
            return info != null ? info : new ExecutorInfo("<unregistered executor>", 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("SCHEDULED EXECUTOR ISSUES DETECTED:\n");

            if (!notShutdownExecutors.isEmpty()) {
                sb.append("  Executors Not Shut Down:\n");
                for (ScheduledExecutorService executor : notShutdownExecutors) {
                    ExecutorInfo info = infoFor(executor);
                    sb.append("    - ").append(info.name).append("\n");
                }
                sb.append("  Why: A ScheduledExecutorService that is never shut down continues scheduling tasks forever and\n" +
                        "       keeps its worker threads alive, preventing JVM exit and leaking OS thread resources.\n");
                sb.append("  Fix: Always call shutdown() or shutdownNow() in a finally block; Java 19+: use try-with-resources\n");
            }

            if (!longRunningTasks.isEmpty()) {
                sb.append("  Long Running Tasks (>1s):\n");
                for (String taskInfo : longRunningTasks) {
                    sb.append("    - ").append(taskInfo).append("\n");
                }
                sb.append("  Warning: Long tasks may delay other scheduled tasks\n");
            }

            if (exceptionInTasks > 0) {
                sb.append("  Exceptions in Scheduled Tasks: ").append(exceptionInTasks).append("\n");
                sb.append("  Why: ScheduledExecutorService silently cancels a recurring task if its Runnable throws an unchecked exception.\n" +
                        "       The task simply stops running with no log entry or notification — a critical background job can\n" +
                        "       vanish unnoticed.\n");
                sb.append("  Fix: Wrap the task body in try/catch Throwable: () -> { try { work(); } catch (Throwable t) { log(t); } }\n");
            }

            if (!hasIssues()) {
                sb.append("  No ScheduledExecutorService issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal executor information.
     */
    static class ExecutorInfo {
        final String name;
        final int corePoolSize;
        int scheduledTasks = 0;
        int runningTasks = 0;
        boolean shutdown = false;

        ExecutorInfo(String name, int corePoolSize) {
            this.name = name;
            this.corePoolSize = corePoolSize;
        }

        synchronized void recordSchedule(String taskName) {
            scheduledTasks++;
        }

        synchronized void recordTaskStart(String taskName) {
            runningTasks++;
        }

        synchronized void recordTaskComplete(String taskName, long durationMs) {
            runningTasks--;
        }
    }
}
