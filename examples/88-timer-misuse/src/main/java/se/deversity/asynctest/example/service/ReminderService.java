package se.deversity.asynctest.example.service;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Schedules reminder notifications using {@link Timer}.
 *
 * <p>BUG 1: {@link Timer} runs every task on one background thread. A task that takes a while
 * delays every other task behind it, whatever delay each was scheduled with. Eight reminders
 * that each want to fire immediately fire one after another instead.
 *
 * <p>BUG 2: an uncaught exception in a {@link TimerTask} kills that thread, and
 * {@link Timer} responds by cancelling every task still scheduled. Silently. The next
 * {@code schedule()} call throws {@code IllegalStateException}, which is the first anybody
 * hears about it.
 *
 * <p>FIX: {@code ScheduledExecutorService}. It can have more than one thread, and a task that
 * throws kills that task rather than the scheduler.
 *
 * <p>INSTRUMENTATION: TimerDetector times each task from run to complete, and needs to be told
 * when one throws. The hooks below report that lifecycle; they default to no-ops, so the
 * production path never touches the test library. This is the seam, not the bug.
 */
public class ReminderService {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final Timer timer = new Timer("reminder-timer", /*daemon=*/false);
    private final List<String> firedReminders = new CopyOnWriteArrayList<>();

    private volatile Consumer<String> onScheduled = taskName -> { };

    private volatile Consumer<String> onRun = taskName -> { };

    private volatile Consumer<String> onComplete = taskName -> { };

    private volatile BiConsumer<String, Throwable> onException = (taskName, failure) -> { };

    private volatile Runnable onCancel = () -> { };

    /**
     * Schedule a reminder that does a little work.
     *
     * @param message the reminder message
     * @param delayMs delay before the task runs
     * @return a latch released when the task has finished
     */
    public CountDownLatch scheduleReminder(String message, long delayMs) {
        return scheduleReminder(message, delayMs, 5L);
    }

    /**
     * Schedule a reminder whose work takes {@code workMillis}.
     *
     * <p>BUG: that work happens on the timer's one and only thread, so it is also the delay
     * every reminder behind this one inherits.
     *
     * @param message     the reminder message
     * @param delayMs     delay before the task runs
     * @param workMillis  how long the task occupies the timer thread
     * @return a latch released when the task has finished
     */
    public CountDownLatch scheduleReminder(String message, long delayMs, long workMillis) {
        CountDownLatch done = new CountDownLatch(1);
        String taskName = "reminder-" + SEQUENCE.incrementAndGet();
        onScheduled.accept(taskName);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                onRun.accept(taskName);
                try {
                    Thread.sleep(workMillis);   // BUG: holds the single timer thread
                    firedReminders.add(message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    onComplete.accept(taskName);
                    done.countDown();
                }
            }
        }, delayMs);
        return done;
    }

    /**
     * Schedule a reminder whose task throws.
     *
     * <p>BUG: in {@link Timer} this kills the timer thread and cancels everything still
     * scheduled, without telling anybody.
     *
     * @param delayMs delay before the task runs
     * @return a latch released once the task has thrown
     */
    public CountDownLatch scheduleFailingReminder(long delayMs) {
        CountDownLatch done = new CountDownLatch(1);
        String taskName = "failing-reminder-" + SEQUENCE.incrementAndGet();
        onScheduled.accept(taskName);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                onRun.accept(taskName);
                RuntimeException failure = new IllegalStateException("reminder backend unreachable");
                onException.accept(taskName, failure);
                done.countDown();
                throw failure;              // BUG: takes the timer thread down with it
            }
        }, delayMs);
        return done;
    }

    /**
     * {@return the reminders that have already fired}
     */
    public List<String> getFiredReminders() {
        return List.copyOf(firedReminders);
    }

    /**
     * Cancel the timer and all pending tasks.
     */
    public void cancel() {
        timer.cancel();
        onCancel.run();
    }

    /**
     * {@return the underlying Timer, which the detector tracks by identity}
     */
    public Timer getTimer() {
        return timer;
    }

    /**
     * Installs the hooks TimerDetector needs. No-ops by default.
     *
     * @param scheduled called with the task label as it is scheduled
     * @param run       called with the task label as it starts
     * @param complete  called with the task label as it finishes
     * @param exception called with the task label and the throwable that is about to escape
     * @param cancel    called after the timer is cancelled
     */
    public void observeTimer(Consumer<String> scheduled, Consumer<String> run,
                             Consumer<String> complete, BiConsumer<String, Throwable> exception,
                             Runnable cancel) {
        this.onScheduled = scheduled;
        this.onRun = run;
        this.onComplete = complete;
        this.onException = exception;
        this.onCancel = cancel;
    }
}
