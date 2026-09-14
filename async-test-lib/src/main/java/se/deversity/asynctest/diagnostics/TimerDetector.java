package se.deversity.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects misuse of {@link java.util.Timer} in concurrent code.
 *
 * <p>{@code java.util.Timer} has several well-known concurrency pitfalls:
 * <ul>
 *   <li><strong>Single execution thread</strong> — all scheduled tasks run
 *       sequentially on a single daemon thread; a long-running task starves
 *       all subsequent tasks.</li>
 *   <li><strong>Exception propagates to the timer thread</strong> — an uncaught
 *       exception in any {@code TimerTask.run()} terminates the timer's thread,
 *       silently cancelling <em>all</em> remaining tasks with no error reported.</li>
 *   <li><strong>Non-reusable after cancel</strong> — once {@code timer.cancel()} is
 *       called (or the thread dies), no new tasks can be scheduled.</li>
 *   <li><strong>Deprecated for most use-cases</strong> — prefer
 *       {@code ScheduledExecutorService} which uses a thread pool, propagates
 *       exceptions via {@code Future}, and survives individual task failures.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectTimerIssues = true)
 * void testTimerUsage() {
 *     Timer timer = new Timer("my-timer");
 *     AsyncTestContext.timerMonitor()
 *         .registerTimer(timer, "my-timer");
 *
 *     timer.schedule(new TimerTask() {
 *         public void run() {
 *             AsyncTestContext.timerMonitor()
 *                 .recordTaskRun(timer, "my-timer", "task-1");
 *         }
 *     }, 0, 100);
 * }
 * }</pre>
 */
public class TimerDetector {

    private static class TimerState {
        final String name;
        final AtomicInteger scheduledTasks   = new AtomicInteger(0);
        final AtomicInteger completedTasks   = new AtomicInteger(0);
        final AtomicInteger failedTasks      = new AtomicInteger(0);
        final AtomicInteger longRunningTasks = new AtomicInteger(0);
        volatile boolean cancelled = false;
        /**
         * An exception was recorded from a thread that is not a timer thread, so there is no
         * thread to ask and the record is taken at its word, as the recording API always has.
         */
        volatile boolean threadDied = false;
        /**
         * The timer threads an exception was recorded on. Whether one of them died is asked of
         * the thread at analysis rather than assumed at the record: a task that catches its
         * exception, records it and carries on leaves the timer running (#567).
         */
        final Set<Thread> threadsThatRecordedAnException = ConcurrentHashMap.newKeySet();
        /** Tracks per-task durations to identify long-running tasks, in {@code nanoTime}. */
        final Map<String, Long> taskStartTimes = new ConcurrentHashMap<>();

        TimerState(String name) {
            this.name = name;
        }
    }

    /** Threshold (ms) beyond which a task is considered long-running. */
    private static final long LONG_TASK_THRESHOLD_MS = 100;

    private final Map<Integer, TimerState> timers = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a {@code Timer} instance for monitoring.
     *
     * @param timer the Timer to monitor
     * @param name  a descriptive label used in reports
     */
    public void registerTimer(java.util.Timer timer, String name) {
        if (!enabled || timer == null) return;
        timers.putIfAbsent(System.identityHashCode(timer),
                new TimerState(name != null ? name : "timer@" + System.identityHashCode(timer)));
    }

    /**
     * Record that a task has been scheduled on the timer.
     *
     * @param timer    the Timer instance
     * @param name     the label (should match registration)
     * @param taskName a descriptive name for the task
     */
    public void recordTaskSchedule(java.util.Timer timer, String name, String taskName) {
        if (!enabled || timer == null || taskName == null) return;
        TimerState state = resolve(timer, name);
        state.scheduledTasks.incrementAndGet();
        // No start time here. It was stored under a key recordTaskComplete never removes, so it
        // grew by one entry per schedule and measured nothing; a task's run starts at recordTaskRun.
    }

    /**
     * Record that a task has started execution.
     *
     * @param timer    the Timer instance
     * @param name     the label (should match registration)
     * @param taskName a descriptive name for the task
     */
    public void recordTaskRun(java.util.Timer timer, String name, String taskName) {
        if (!enabled || timer == null || taskName == null) return;
        TimerState state = resolve(timer, name);
        state.taskStartTimes.put(taskName, System.nanoTime());
    }

    /**
     * Record that a task finished successfully.
     *
     * @param timer    the Timer instance
     * @param name     the label (should match registration)
     * @param taskName a descriptive name for the task
     */
    public void recordTaskComplete(java.util.Timer timer, String name, String taskName) {
        if (!enabled || timer == null || taskName == null) return;
        TimerState state = resolve(timer, name);
        state.completedTasks.incrementAndGet();

        Long startTime = state.taskStartTimes.remove(taskName);
        if (startTime != null) {
            // Monotonic: a wall clock stepped by NTP between run and complete made a task look
            // longer, or negative, without it having run for that long.
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (elapsed > LONG_TASK_THRESHOLD_MS) {
                state.longRunningTasks.incrementAndGet();
            }
        }
    }

    /**
     * Record that a task threw an uncaught exception.
     *
     * <p>In {@code java.util.Timer} this kills the timer thread, which silently
     * cancels all remaining tasks.
     *
     * @param timer     the Timer instance
     * @param name      the label (should match registration)
     * @param taskName  a descriptive name for the task
     * @param exception the uncaught exception (may be {@code null})
     */
    public void recordTaskException(java.util.Timer timer, String name,
                                    String taskName, Throwable exception) {
        if (!enabled || timer == null) return;
        TimerState state = resolve(timer, name);
        state.failedTasks.incrementAndGet();
        Thread current = Thread.currentThread();
        if (TIMER_THREAD_CLASS.equals(current.getClass().getName())) {
            state.threadsThatRecordedAnException.add(current);
        } else {
            state.threadDied = true;
        }
    }

    /**
     * The class of every thread a {@link java.util.Timer} runs its tasks on. Package-private in
     * the JDK, so matched by name; a record from any other thread cannot be checked against a
     * timer thread and keeps its old meaning.
     */
    private static final String TIMER_THREAD_CLASS = "java.util.TimerThread";

    /**
     * How long analysis waits for a timer thread to finish dying after a task's exception escaped.
     * The thread exits as soon as the exception leaves {@code run()}, so this bounds a race rather
     * than measuring anything; a thread still alive after it kept running, which is what a caught
     * exception looks like, and a miss here errs towards silence.
     */
    private static final long DEATH_WAIT_MS = 1_000;

    private static boolean anyDied(Set<Thread> threads) {
        for (Thread thread : threads) {
            try {
                thread.join(DEATH_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!thread.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Record that {@code timer.cancel()} was called.
     *
     * @param timer the Timer instance
     * @param name  the label (should match registration)
     */
    public void recordTimerCancel(java.util.Timer timer, String name) {
        if (!enabled || timer == null) return;
        TimerState state = resolve(timer, name);
        state.cancelled = true;
    }

    private TimerState resolve(java.util.Timer timer, String name) {
        return timers.computeIfAbsent(System.identityHashCode(timer),
                k -> new TimerState(name != null ? name : "timer@" + k));
    }

    /**
     * Analyse Timer usage and return a report.
     *
     * @return the findings this detector collected during the run
     */
    public TimerReport analyze() {
        TimerReport report = new TimerReport();

        for (TimerState state : timers.values()) {
            report.totalTimers++;

            if (state.threadDied || anyDied(state.threadsThatRecordedAnException)) {
                state.threadDied = true;
                report.timerThreadFailures.add(String.format(
                        "%s: timer thread died due to uncaught exception in a task — "
                        + "%d scheduled task(s) silently cancelled",
                        state.name,
                        state.scheduledTasks.get() - state.completedTasks.get() - state.failedTasks.get()));
            }

            if (state.longRunningTasks.get() > 0) {
                report.longRunningTaskWarnings.add(String.format(
                        "%s: %d task(s) exceeded %d ms — starving subsequent tasks "
                        + "(all tasks share one thread in java.util.Timer)",
                        state.name, state.longRunningTasks.get(), LONG_TASK_THRESHOLD_MS));
            }

            int total = state.scheduledTasks.get();
            if (total > 0) {
                report.timerActivity.put(state.name, String.format(
                        "scheduled: %d, completed: %d, failed: %d, long-running: %d, cancelled: %b",
                        state.scheduledTasks.get(), state.completedTasks.get(),
                        state.failedTasks.get(), state.longRunningTasks.get(), state.cancelled));
            }

            // Always warn about Timer usage regardless of errors found
            if (total > 0 && !state.threadDied && state.longRunningTasks.get() == 0) {
                report.usageWarnings.add(String.format(
                        "%s: java.util.Timer is deprecated; consider ScheduledExecutorService "
                        + "which handles task exceptions gracefully and supports thread pools.",
                        state.name));
            }
        }

        return report;
    }

    // ---- Report ----------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class TimerReport {

        int totalTimers = 0;
        final java.util.List<String> timerThreadFailures    = new java.util.ArrayList<>();
        final java.util.List<String> longRunningTaskWarnings = new java.util.ArrayList<>();
        final java.util.List<String> usageWarnings          = new java.util.ArrayList<>();
        final Map<String, String>    timerActivity          = new ConcurrentHashMap<>();

        /**
         * Returns {@code true} when timer thread failures or long-running tasks were detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
        public boolean hasIssues() {
            return !timerThreadFailures.isEmpty() || !longRunningTaskWarnings.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("TIMER ISSUES DETECTED:\n");

            if (!timerThreadFailures.isEmpty()) {
                sb.append("""
  Why: An uncaught exception in a TimerTask cancels ALL future tasks on that Timer silently.
       The timer continues to exist but never fires another task.
""");
                sb.append("  Timer Thread Failures (tasks silently cancelled):\n");
                for (String issue : timerThreadFailures) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!longRunningTaskWarnings.isEmpty()) {
                sb.append("  Long-Running Task Warnings (task starvation):\n");
                for (String w : longRunningTaskWarnings) {
                    sb.append("    - ").append(w).append("\n");
                }
            }

            if (!usageWarnings.isEmpty()) {
                sb.append("  Usage Warnings:\n");
                for (String w : usageWarnings) {
                    sb.append("    - ").append(w).append("\n");
                }
            }

            if (!timerActivity.isEmpty()) {
                sb.append("  Timer Activity:\n");
                for (Map.Entry<String, String> e : timerActivity.entrySet()) {
                    sb.append("    - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No critical issues detected.\n");
            }

            sb.append("""
  Why: A Timer that is never cancelled keeps its TimerThread alive forever, preventing JVM exit
       and consuming an OS thread. Scheduled tasks continue firing even after the test ends,
       interfering with subsequent tests.
  Fix: replace java.util.Timer with ScheduledExecutorService\
""");
            return sb.toString();
        }
    }
}
