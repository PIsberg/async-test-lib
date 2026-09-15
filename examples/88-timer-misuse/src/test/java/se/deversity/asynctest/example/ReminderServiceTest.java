package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.TimerDetector;
import se.deversity.asynctest.example.service.ReminderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ReminderService.
 *
 * ========================================================================
 * DETECTOR: TimerDetector
 * ========================================================================
 *
 * THE BUG:
 * java.util.Timer runs every task on one background thread. A task that takes a
 * while delays every task that falls due behind it, whatever delay each was
 * scheduled with. And a task that throws kills that thread, at which point Timer
 * silently cancels everything still scheduled.
 *
 * WHAT THE DETECTOR REPORTS:
 * Exactly those two. hasIssues() gates on timerThreadFailures and
 * starvedTaskWarnings. It does not report a timer that was never cancelled,
 * which is what this example used to claim (issue #346).
 *
 * Starvation is observed, not inferred from a duration (#575): a reminder is
 * reported when it fell due, by its own TimerTask.scheduledExecutionTime(), while
 * another reminder still held the timer thread. A slow reminder with nothing due
 * behind it starves nobody and is not reported, however long it ran; the detector
 * used to call anything over 100 ms long-running, which a GC pause could trip.
 *
 * DETECTOR ENABLED HERE:
 * TimerDetector. It is the only one this demonstration switches on, so it is the
 * only one that can report.
 *
 * FIX:
 * ScheduledExecutorService. It can have more than one thread, and a task that
 * throws kills that task rather than the scheduler.
 */
class ReminderServiceTest {

    /** Long enough that a reminder falling due a moment later lands inside it. */
    private static final long SLOW_WORK_MS = 150L;

    private ReminderService service;

    @BeforeEach
    void setUp() {
        service = new ReminderService();
    }

    @AfterEach
    void tearDown() {
        service.cancel();
    }

    @Test
    void test_singleThread_schedulesReminder() throws Exception {
        CountDownLatch fired = service.scheduleReminder("meeting at 3pm", 0);

        assertTrue(fired.await(5, TimeUnit.SECONDS), "the reminder should fire");
        assertEquals(1, service.getFiredReminders().size());
    }

    @Test
    void test_singleThread_timerIsNotNull() {
        assertNotNull(service.getTimer());
    }

    /**
     * The starvation, with no detector involved: three reminders all scheduled to fire now,
     * on one thread, take at least three times as long as one of them.
     */
    @Test
    void test_oneThreadMeansTheyQueue() throws Exception {
        long start = System.nanoTime();
        CountDownLatch first = service.scheduleReminder("a", 0, SLOW_WORK_MS);
        CountDownLatch second = service.scheduleReminder("b", 0, SLOW_WORK_MS);
        CountDownLatch third = service.scheduleReminder("c", 0, SLOW_WORK_MS);

        assertTrue(first.await(5, TimeUnit.SECONDS));
        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertTrue(third.await(5, TimeUnit.SECONDS));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 3 * SLOW_WORK_MS,
                "three tasks that each take " + SLOW_WORK_MS + "ms ran in " + elapsedMs
                        + "ms, which would mean they had more than one thread");
    }

    /**
     * The detector's positive direction, on the starvation half: a quick reminder falls due while
     * a slow one is still running, and has to wait for it.
     */
    @Test
    void testTimerDetector_reminderDueBehindASlowOne_reports() throws Exception {
        TimerDetector detector = new TimerDetector();
        CountDownLatch slowStarted = new CountDownLatch(1);
        wire(detector, slowStarted);
        detector.registerTimer(service.getTimer(), "reminder-timer");

        CountDownLatch slow = service.scheduleReminder("slow", 0, SLOW_WORK_MS);
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS), "the slow reminder should start");
        // Due 1 ms from now, while the slow reminder has most of its 150 ms still to run.
        CountDownLatch quick = service.scheduleReminder("quick", 1, 1L);

        assertTrue(slow.await(5, TimeUnit.SECONDS));
        assertTrue(quick.await(5, TimeUnit.SECONDS));

        assertTrue(detector.analyze().hasIssues(),
                "the quick reminder fell due while the slow one held the only timer thread: "
                        + detector.analyze());
    }

    /**
     * The other direction on the same half: a slow reminder with nothing due behind it. It ran
     * well past the 100 ms the detector used to call long-running, and starved nobody.
     */
    @Test
    void testTimerDetector_slowReminderAlone_isSilent() throws Exception {
        TimerDetector detector = new TimerDetector();
        wire(detector, new CountDownLatch(1));
        detector.registerTimer(service.getTimer(), "reminder-timer");

        assertTrue(service.scheduleReminder("slow", 0, SLOW_WORK_MS).await(5, TimeUnit.SECONDS));

        assertFalse(detector.analyze().hasIssues(),
                "no reminder fell due while the slow one ran, so none waited: " + detector.analyze());
    }

    /**
     * And on the thread-death half, which is the more brutal of the two: after this, Timer has
     * cancelled everything still scheduled and says nothing.
     */
    @Test
    void testTimerDetector_taskThrew_reports() throws Exception {
        TimerDetector detector = new TimerDetector();
        wire(detector, new CountDownLatch(1));
        detector.registerTimer(service.getTimer(), "reminder-timer");

        assertTrue(service.scheduleFailingReminder(0).await(5, TimeUnit.SECONDS));

        assertTrue(detector.analyze().hasIssues(),
                "an uncaught exception in a TimerTask takes the timer thread with it");
    }

    /**
     * A quick task that completes is a Timer doing its job. The report still carries a usage
     * note about java.util.Timer being deprecated, and deliberately does not gate on it.
     */
    @Test
    void testTimerDetector_quickTask_isSilent() throws Exception {
        TimerDetector detector = new TimerDetector();
        wire(detector, new CountDownLatch(1));
        detector.registerTimer(service.getTimer(), "reminder-timer");

        assertTrue(service.scheduleReminder("quick", 0, 1L).await(5, TimeUnit.SECONDS));

        assertFalse(detector.analyze().hasIssues(),
                "a task that finished promptly starved nobody");
    }

    private void wire(TimerDetector detector, CountDownLatch firstRunStarted) {
        service.observeTimer(
                name -> detector.recordTaskSchedule(service.getTimer(), "reminder-timer", name),
                (name, task) -> {
                    detector.recordTaskRun(service.getTimer(), "reminder-timer", task, name);
                    firstRunStarted.countDown();
                },
                name -> detector.recordTaskComplete(service.getTimer(), "reminder-timer", name),
                (name, failure) -> detector.recordTaskException(
                        service.getTimer(), "reminder-timer", name, failure),
                () -> detector.recordTimerCancel(service.getTimer(), "reminder-timer"));
    }

    /** Spreads the demonstration's due times 10 ms apart, so each falls inside a 150 ms run. */
    private static final AtomicInteger ARRIVALS = new AtomicInteger();

    /**
     * The bug: eight reminders due 10 ms apart, all on one thread, each taking 150 ms. Every one
     * after the first falls due while an earlier one still holds the thread.
     *
     * invocations is 1 because that is already the demonstration. Eight tasks that each take
     * 150ms, serialised on the timer's single thread, is over a second of queueing; repeating
     * the round multiplies the wall time without adding anything to the report.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      reminder-timer: N task(s) fell due while another task held the timer's only thread
     * 3. Fix: ScheduledExecutorService with a thread pool
     */
    @Disabled("Remove @Disabled to see bug detected by TimerDetector")
    @AsyncTest(threads = 8, invocations = 1, detectAll = false,
            detectTimerIssues = true, failOn = FailOn.LOW)
    void test_concurrent_detectsTimerIssues() throws Exception {
        TimerDetector detector = AsyncTestContext.timerMonitor();
        detector.registerTimer(service.getTimer(), "reminder-timer");
        service.observeTimer(
                name -> detector.recordTaskSchedule(service.getTimer(), "reminder-timer", name),
                (name, task) -> detector.recordTaskRun(service.getTimer(), "reminder-timer", task, name),
                name -> detector.recordTaskComplete(service.getTimer(), "reminder-timer", name),
                (name, failure) -> detector.recordTaskException(
                        service.getTimer(), "reminder-timer", name, failure),
                () -> detector.recordTimerCancel(service.getTimer(), "reminder-timer"));

        CountDownLatch fired = service.scheduleReminder(
                "alert-" + Thread.currentThread().threadId(), 10L * ARRIVALS.getAndIncrement(),
                SLOW_WORK_MS);

        // Waiting matters: a reminder is judged when it runs, and the run is analysed as soon as
        // the last body returns.
        assertTrue(fired.await(30, TimeUnit.SECONDS), "the reminder should eventually fire");
    }
}
