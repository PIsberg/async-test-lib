package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ReminderService;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Demonstrates {@code TimerDetector}.
 *
 * <p>The passing tests show single-threaded reminder scheduling works. The
 * disabled test reveals the issues: under concurrent load, many tasks pile up
 * on the single-threaded {@code Timer} and it is never cancelled, leaving a
 * background thread alive after the test.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class ReminderServiceTest {

    private ReminderService service;

    @BeforeEach
    void setUp() {
        service = new ReminderService();
    }

    @AfterEach
    void tearDown() {
        // In the passing tests we always clean up.
        service.cancel();
    }

    @Test
    void test_singleThread_schedulesReminder() {
        service.scheduleReminder("meeting at 3pm", 1000);
        assertNotNull(service.getFiredReminders());
    }

    @Test
    void test_singleThread_timerIsNotNull() {
        assertNotNull(service.getTimer());
    }

    /**
     * Remove {@code @Disabled} to see {@code TimerDetector} report a timer
     * that accumulates tasks without ever being cancelled.
     *
     * <p>The detector is informed about the timer registration and each
     * scheduled task via the context accessor. Because {@code cancel()} is
     * never called, the detector flags the timer as leaking.
     */
    @Disabled("Remove @Disabled to see bug detected by TimerDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectTimerIssues = true, failOn = FailOn.LOW)
    void test_concurrent_detectsTimerIssues() {
        var detector = AsyncTestContext.timerMonitor();
        String timerName = "reminder-timer";
        String taskName = "reminder-" + Thread.currentThread().threadId();

        // Register the timer instance with the detector.
        detector.registerTimer(service.getTimer(), timerName);

        // Record that a task is being scheduled.
        detector.recordTaskSchedule(service.getTimer(), timerName, taskName);

        // Schedule the reminder — no cancel() follows.
        service.scheduleReminder("alert-" + Thread.currentThread().threadId(), 50);

        // BUG: cancel() is never called — timer thread and pending tasks leak.
    }
}
