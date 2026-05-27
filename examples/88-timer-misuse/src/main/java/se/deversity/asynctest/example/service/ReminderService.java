package se.deversity.asynctest.example.service;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Schedules reminder notifications using {@link Timer}.
 *
 * <p>BUG 1: {@link Timer} uses a single background thread. A slow task delays
 * every subsequent task regardless of their individual delays.
 *
 * <p>BUG 2: {@link #cancel()} is never called in the concurrent test, so the
 * timer's background thread keeps running and all pending tasks accumulate.
 */
public class ReminderService {

    private final Timer timer = new Timer("reminder-timer", /*daemon=*/false);
    private final List<String> firedReminders = new CopyOnWriteArrayList<>();

    /**
     * Schedule a reminder to fire after {@code delayMs} milliseconds.
     *
     * @param message the reminder message
     * @param delayMs delay in milliseconds before the task runs
     */
    public void scheduleReminder(String message, long delayMs) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Simulate slow processing — blocks the single Timer thread.
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                firedReminders.add(message);
            }
        }, delayMs);
    }

    /**
     * Returns the list of reminders that have already fired.
     */
    public List<String> getFiredReminders() {
        return List.copyOf(firedReminders);
    }

    /**
     * Cancel the timer and all pending tasks.
     * Must be called to avoid leaking the timer thread — but is never
     * called in the concurrent test.
     */
    public void cancel() {
        timer.cancel();
    }

    public Timer getTimer() {
        return timer;
    }
}
