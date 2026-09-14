package se.deversity.asynctest.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

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
 * <p><strong>How starvation is decided (#575).</strong> A task is reported as starved when it
 * fell due while a different task held the timer's thread, which is starvation observed rather
 * than inferred from a duration. The due instant is the task's own
 * {@link java.util.TimerTask#scheduledExecutionTime()}, so the recording has to come from inside
 * {@code run()} and carry the task: {@link #recordTaskRun(java.util.Timer, String,
 * java.util.TimerTask, String)}. Who held the thread at that instant comes from the runs recorded
 * on the timer thread, opened by {@code recordTaskRun} and closed by {@code recordTaskComplete} or
 * {@code recordTaskException}. There is no duration threshold: a lone task that runs for a second
 * starves nobody, and a GC pause that stretches a short task is not a finding unless another task
 * fell due inside it.
 *
 * <p>The boundaries, all towards silence:
 * <ul>
 *   <li>Two tasks due at the same instant are ordered by the timer, and the second one's wait is
 *       not reported, however long the first ran: the due time did not fall inside a run.</li>
 *   <li>A fixed-delay repetition ({@code schedule(task, delay, period)}) reports the instant the
 *       timer picked it rather than when it fell due, so it is never seen waiting.</li>
 *   <li>A task that falls due inside its own previous execution (a fixed-rate task overrunning its
 *       period) is not reported as starving another.</li>
 *   <li>Instants are milliseconds, so an overlap shorter than a clock tick can be missed.</li>
 *   <li>Records that name a task but carry no {@code TimerTask} still mark who held the thread,
 *       but cannot say when a task fell due, so they never decide a starvation.</li>
 * </ul>
 * The one direction that can still fire on correct code: a GC pause inside one task's run, during
 * which another task fell due, is reported; the other task did wait, and the report says for whom.
 *
 * <p>Usage, recorded from inside the task:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectTimerIssues = true)
 * void testTimerUsage() {
 *     Timer timer = new Timer("my-timer");
 *     TimerDetector detector = AsyncTestContext.timerMonitor();
 *     detector.registerTimer(timer, "my-timer");
 *
 *     timer.schedule(new TimerTask() {
 *         public void run() {
 *             detector.recordTaskRun(timer, "my-timer", this, "task-1");
 *             doWork();
 *             detector.recordTaskComplete(timer, "my-timer", "task-1");
 *         }
 *     }, 0, 100);
 * }
 * }</pre>
 */
public class TimerDetector {

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

    /** How many starvations each timer quotes in the report; the count covers all of them. */
    private static final int QUOTED_STARVATIONS = 3;

    /** One task's occupation of a timer thread, from its recorded run to its recorded end. */
    private static final class Run {
        /** The {@code TimerTask} when the record carried it, otherwise the task's name. */
        final Object task;
        final String label;
        final long startMs;
        long endMs = -1;

        Run(Object task, String label, long startMs) {
            this.task = task;
            this.label = label;
            this.startMs = startMs;
        }
    }

    /**
     * The runs one timer thread recorded, newest first. Only that thread writes it, and the runs
     * on one thread are consecutive, so a lookup walks back only as far as the instant it asks
     * about.
     */
    private static final class RunHistory {
        /** Deep enough for any burst of tasks that falls due inside one run of a test. */
        private static final int DEPTH = 64;

        private @Nullable Run open;
        private final Deque<Run> finished = new ArrayDeque<>();

        synchronized void start(Object task, String label, long nowMs) {
            // A run still open was never closed by a complete or an exception record; its end is
            // unknown, so it is dropped rather than guessed, which can only miss a starvation.
            open = new Run(task, label, nowMs);
        }

        synchronized void end(long nowMs) {
            if (open == null) {
                return;
            }
            open.endMs = nowMs;
            finished.addFirst(open);
            if (finished.size() > DEPTH) {
                finished.removeLast();
            }
            open = null;
        }

        /**
         * {@return the finished run of a different task that held the thread at {@code instantMs},
         * or {@code null}} Strict on both sides: a task due in the same millisecond a run started
         * or ended was not observed waiting.
         */
        synchronized @Nullable Run heldAt(long instantMs, Object task) {
            for (Run run : finished) {
                if (run.endMs <= instantMs) {
                    return null;
                }
                if (run.startMs < instantMs) {
                    return Objects.equals(run.task, task) ? null : run;
                }
            }
            return null;
        }
    }

    private static class TimerState {
        final String name;
        final AtomicInteger scheduledTasks = new AtomicInteger(0);
        final AtomicInteger completedTasks = new AtomicInteger(0);
        final AtomicInteger failedTasks    = new AtomicInteger(0);
        final AtomicInteger starvedTasks   = new AtomicInteger(0);
        final List<String> quotedStarvations = new CopyOnWriteArrayList<>();
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
        /** The runs recorded on each timer thread; only timer threads are tracked. */
        final Map<Thread, RunHistory> runsByTimerThread = new ConcurrentHashMap<>();

        TimerState(String name) {
            this.name = name;
        }
    }

    private final Map<IdentityKey, TimerState> timers = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a {@code Timer} instance for monitoring.
     *
     * @param timer the Timer to monitor
     * @param name  a descriptive label used in reports
     */
    public void registerTimer(java.util.Timer timer, String name) {
        if (!enabled || timer == null) return;
        timers.putIfAbsent(new IdentityKey(timer), new TimerState(label(timer, name)));
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
    }

    /**
     * Record that a task has started execution.
     *
     * <p>This form does not carry the task, so it cannot say when the task fell due. Called from
     * inside {@code run()}, it still marks the timer thread as held until the matching complete or
     * exception record, which lets another task's record find it; it never decides a starvation
     * itself. Prefer {@link #recordTaskRun(java.util.Timer, String, java.util.TimerTask, String)}.
     *
     * @param timer    the Timer instance
     * @param name     the label (should match registration)
     * @param taskName a descriptive name for the task
     */
    public void recordTaskRun(java.util.Timer timer, String name, String taskName) {
        if (!enabled || timer == null || taskName == null) return;
        RunHistory runs = runsOnThisTimerThread(resolve(timer, name));
        if (runs != null) {
            runs.start(taskName, taskName, System.currentTimeMillis());
        }
    }

    /**
     * Record that a task has started execution, from inside its {@code run()}.
     *
     * <p>Reads {@link java.util.TimerTask#scheduledExecutionTime()} and reports the task as starved
     * when that instant fell inside a run of a different task on this timer thread, recorded from
     * start to complete. Records made off the timer thread are counted as neither.
     *
     * @param timer    the Timer instance
     * @param name     the label (should match registration)
     * @param task     the task whose {@code run()} is making this call, normally {@code this}
     * @param taskName a descriptive name for the task
     * @since 1.12.1
     */
    public void recordTaskRun(java.util.Timer timer, String name, java.util.TimerTask task,
                              String taskName) {
        if (!enabled || timer == null || task == null || taskName == null) return;
        TimerState state = resolve(timer, name);
        RunHistory runs = runsOnThisTimerThread(state);
        if (runs == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long dueMs = task.scheduledExecutionTime();
        Run holder = runs.heldAt(dueMs, task);
        if (holder != null) {
            state.starvedTasks.incrementAndGet();
            if (state.quotedStarvations.size() < QUOTED_STARVATIONS) {
                state.quotedStarvations.add(String.format(
                        "'%s' fell due %d ms into a %d ms run of '%s' and started %d ms late",
                        taskName, dueMs - holder.startMs, holder.endMs - holder.startMs,
                        holder.label, Math.max(0, nowMs - dueMs)));
            }
        }
        runs.start(task, taskName, nowMs);
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
        endRunOnThisTimerThread(state);
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
        endRunOnThisTimerThread(state);
        Thread current = Thread.currentThread();
        if (isTimerThread(current)) {
            state.threadsThatRecordedAnException.add(current);
        } else {
            state.threadDied = true;
        }
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

    private static boolean isTimerThread(Thread thread) {
        return TIMER_THREAD_CLASS.equals(thread.getClass().getName());
    }

    private static @Nullable RunHistory runsOnThisTimerThread(TimerState state) {
        Thread current = Thread.currentThread();
        return isTimerThread(current)
                ? state.runsByTimerThread.computeIfAbsent(current, t -> new RunHistory())
                : null;
    }

    private static void endRunOnThisTimerThread(TimerState state) {
        RunHistory runs = state.runsByTimerThread.get(Thread.currentThread());
        if (runs != null) {
            runs.end(System.currentTimeMillis());
        }
    }

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

    private static String label(java.util.Timer timer, String name) {
        return name != null ? name : "timer@" + System.identityHashCode(timer);
    }

    private TimerState resolve(java.util.Timer timer, String name) {
        return timers.computeIfAbsent(new IdentityKey(timer), k -> new TimerState(label(timer, name)));
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

            int starved = state.starvedTasks.get();
            if (starved > 0) {
                report.starvedTaskWarnings.add(String.format(
                        "%s: %d task(s) fell due while another task held the timer's only thread, "
                        + "and waited for it: %s",
                        state.name, starved, String.join("; ", state.quotedStarvations)));
            }

            int total = state.scheduledTasks.get();
            if (total > 0) {
                report.timerActivity.put(state.name, String.format(
                        "scheduled: %d, completed: %d, failed: %d, starved: %d, cancelled: %b",
                        state.scheduledTasks.get(), state.completedTasks.get(),
                        state.failedTasks.get(), starved, state.cancelled));
            }

            // Always warn about Timer usage regardless of errors found
            if (total > 0 && !state.threadDied && starved == 0) {
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
        final List<String> timerThreadFailures = new ArrayList<>();
        final List<String> starvedTaskWarnings = new ArrayList<>();
        final List<String> usageWarnings       = new ArrayList<>();
        final Map<String, String>    timerActivity       = new ConcurrentHashMap<>();

        /**
         * Returns {@code true} when a timer thread died, or a task was observed falling due while
         * another task held the timer's thread.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
        public boolean hasIssues() {
            return !timerThreadFailures.isEmpty() || !starvedTaskWarnings.isEmpty();
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

            if (!starvedTaskWarnings.isEmpty()) {
                sb.append("""
  Why: java.util.Timer runs every task on one thread. A task that falls due while another is
       still running waits for it, whatever delay it was scheduled with.
""");
                sb.append("  Starved Tasks (fell due while another task held the thread):\n");
                for (String w : starvedTaskWarnings) {
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
