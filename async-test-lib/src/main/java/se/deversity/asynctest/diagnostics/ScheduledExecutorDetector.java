package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
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

    /** Task duration, in milliseconds, above which a scheduled task is reported long-running (#756). */
    private static final long LONG_RUNNING_TASK_THRESHOLD_MS = 1000;

    private final Map<ScheduledExecutorService, ExecutorInfo> executorRegistry = new ConcurrentHashMap<>();
    private final Set<ScheduledExecutorService> notShutdownExecutors = ConcurrentHashMap.newKeySet();
    private final Set<String> longRunningTasks = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger exceptionInTasks =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Register a ScheduledExecutorService for monitoring.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param name a label identifying the executor in the report
     * @param corePoolSize the configured core pool size
     */
    public void registerExecutor(ScheduledExecutorService executor, String name, int corePoolSize) {
        if (executor == null) return;
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        executorRegistry.putIfAbsent(executor, new ExecutorInfo(name, corePoolSize));
    }

    /**
     * Record a task being scheduled.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param executorName a label identifying the executor in the report
     * @param taskName a label identifying the task in the report
     */
    public void recordSchedule(ScheduledExecutorService executor, String executorName, String taskName) {
        if (executor == null) return;
        ExecutorInfo info = executorRegistry.get(executor);
        if (info != null) {
            info.recordSchedule(taskName);
        }
    }

    /**
     * Record a task starting execution.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param executorName a label identifying the executor in the report
     * @param taskName a label identifying the task in the report
     */
    public void recordTaskStart(ScheduledExecutorService executor, String executorName, String taskName) {
        if (executor == null) return;
        ExecutorInfo info = executorRegistry.get(executor);
        if (info != null) {
            info.recordTaskStart(taskName);
        }
    }

    /**
     * Record a task completing execution.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param executorName a label identifying the executor in the report
     * @param taskName a label identifying the task in the report
     * @param durationMs the duration in milliseconds
     */
    public void recordTaskComplete(ScheduledExecutorService executor, String executorName, String taskName, long durationMs) {
        if (executor == null) return;
        ExecutorInfo info = executorRegistry.get(executor);
        if (info != null) {
            info.recordTaskComplete(taskName, durationMs);
            if (durationMs > LONG_RUNNING_TASK_THRESHOLD_MS) {
                longRunningTasks.add(executorName + ":" + taskName + " (" + durationMs + "ms)");
            }
        }
    }

    /**
     * Record an exception in a scheduled task.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param executorName a label identifying the executor in the report
     */
    public void recordException(ScheduledExecutorService executor, String executorName) {
        if (executor == null) return;
        exceptionInTasks.incrementAndGet();
    }

    /**
     * Record executor shutdown.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordShutdown(ScheduledExecutorService executor) {
        if (executor == null) return;
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
     *
     * @return the findings this detector collected during the run
     */
    public ScheduledExecutorReport analyze() {
        checkShutdown();
        ScheduledExecutorReport report801 = new ScheduledExecutorReport(
            executorRegistry,
            notShutdownExecutors,
            longRunningTasks,
            exceptionInTasks.get()
        );
        report801.fillStructuredViolations();
        return DetectorFailurePolicy.checkedReport(this, report801);
    }

    /**
     * Report class for ScheduledExecutorService analysis.
     */
    public static class ScheduledExecutorReport {
        private final Map<ScheduledExecutorService, ExecutorInfo> executorRegistry;
        private final Set<ScheduledExecutorService> notShutdownExecutors;
        private final Set<String> longRunningTasks;
        private final int exceptionInTasks;
        /**
         * Creates a ScheduledExecutorReport.
         *
         * @param executorRegistry every registered executor and what was observed on it
         * @param notShutdownExecutors the executors never shut down
         * @param longRunningTasks the tasks that ran past the reporting threshold
         * @param exceptionInTasks the exceptions thrown inside scheduled tasks
         */
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

        /**
         * {@return whether there are issues}
         */
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

        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a Violation per finding, worded as its text line (#801); called once before the report is returned. */
        void fillStructuredViolations() {
            if (!hasIssues()) {
                return;
            }
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(toString()).orElse(IssueSeverity.MEDIUM);
            for (ScheduledExecutorService executor : notShutdownExecutors) {
                structuredViolations.add(new Violation("ScheduledExecutor", severity,
                        infoFor(executor).name + ": never shut down", List.of(), Map.of(), Instant.now()));
            }
            for (String taskInfo : longRunningTasks) {
                structuredViolations.add(new Violation("ScheduledExecutor", severity,
                        "Long-running task: " + taskInfo, List.of(), Map.of(), Instant.now()));
            }
            if (exceptionInTasks > 0) {
                structuredViolations.add(new Violation("ScheduledExecutor", severity,
                        exceptionInTasks + " exception(s) thrown in scheduled tasks", List.of(), Map.of(), Instant.now()));
            }
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
                sb.append("""
  Why: A ScheduledExecutorService that is never shut down continues scheduling tasks forever and
       keeps its worker threads alive, preventing JVM exit and leaking OS thread resources.
""");
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
                sb.append("""
  Why: ScheduledExecutorService silently cancels a recurring task if its Runnable throws an unchecked exception.
       The task simply stops running with no log entry or notification — a critical background job can
       vanish unnoticed.
""");
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
