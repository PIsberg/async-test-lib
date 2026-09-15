package se.deversity.asynctest.diagnostics;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starvation on a {@link Timer} is decided from instants the timer hands out, not from how long
 * a task ran (#575).
 *
 * <p>The detector compared each task's run-to-complete time against 100 ms, and that comparison
 * was the finding. A GC pause or a loaded CI runner pushes a correct, short task past 100 ms, and
 * a lone slow task starves nobody at all. What starvation actually is on a timer with one thread:
 * a task fell due while another task held that thread, and had to wait for it.
 * {@link TimerTask#scheduledExecutionTime()} says when a task fell due, and the recorded runs on
 * the timer thread say who held it at that instant.
 *
 * <p>Every positive test here makes the overlap certain rather than likely: the holding task
 * schedules the other one itself, after its own run was recorded, and then keeps the thread until
 * the wall clock has passed the other task's due time. No sleep races a threshold.
 */
class TimerStarvationModelTest {

    private static final String TIMER = "t";

    /** Keeps the calling thread busy until the wall clock is strictly past {@code instantMs}. */
    private static void holdUntilPast(long instantMs) {
        while (System.currentTimeMillis() <= instantMs) {
            sleepQuietly(1);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A task that records its run and completion and does nothing else. */
    private static TimerTask quick(TimerDetector detector, Timer timer, String label, CountDownLatch done) {
        return new TimerTask() {
            @Override
            public void run() {
                detector.recordTaskRun(timer, TIMER, this, label);
                detector.recordTaskComplete(timer, TIMER, label);
                done.countDown();
            }
        };
    }

    @Test
    @DisplayName("a lone task paused past 100 ms starved nobody and is silent")
    void aLonePausedTaskIsSilent() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("lone", true);
        try {
            detector.registerTimer(timer, TIMER);
            CountDownLatch done = new CountDownLatch(1);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    detector.recordTaskRun(timer, TIMER, this, "slow");
                    sleepQuietly(150); // stands in for a GC pause, or a runner with no spare core
                    detector.recordTaskComplete(timer, TIMER, "slow");
                    done.countDown();
                }
            }, 0);
            assertTrue(done.await(10, SECONDS));

            var report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "nothing fell due while the task ran, so no task waited for it: " + report);
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("records that carry no TimerTask cannot say when a task fell due, and decide nothing")
    void nameOnlyRecordsDecideNoStarvation() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("names", true);
        try {
            detector.registerTimer(timer, TIMER);
            detector.recordTaskRun(timer, TIMER, "slow");
            Thread.sleep(150);
            detector.recordTaskComplete(timer, TIMER, "slow");

            var report = detector.analyze();
            assertFalse(report.hasIssues(), "a duration alone is not starvation: " + report);
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("a task that falls due while another holds the timer thread is reported")
    void aTaskDueWhileAnotherHoldsTheThreadIsReported() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("starved", true);
        try {
            detector.registerTimer(timer, TIMER);
            CountDownLatch waiterRan = new CountDownLatch(1);
            TimerTask waiter = quick(detector, timer, "waiter", waiterRan);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    detector.recordTaskRun(timer, TIMER, this, "holder");
                    timer.schedule(waiter, 2);
                    holdUntilPast(waiter.scheduledExecutionTime());
                    detector.recordTaskComplete(timer, TIMER, "holder");
                }
            }, 0);
            assertTrue(waiterRan.await(10, SECONDS));

            var report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "the waiter fell due while the holder still had the only thread: " + report);
            assertTrue(report.toString().contains("'waiter'") && report.toString().contains("'holder'"),
                    "the report names who waited and who held the thread: " + report);
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("a task that falls due only after the earlier one finished is silent")
    void aTaskDueAfterTheThreadWasReleasedIsSilent() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("released", true);
        try {
            detector.registerTimer(timer, TIMER);
            CountDownLatch firstDone = new CountDownLatch(1);
            timer.schedule(quick(detector, timer, "first", firstDone), 0);
            assertTrue(firstDone.await(10, SECONDS));

            // Scheduled after the first task recorded its completion, so it falls due after it.
            CountDownLatch secondDone = new CountDownLatch(1);
            timer.schedule(quick(detector, timer, "second", secondDone), 2);
            assertTrue(secondDone.await(10, SECONDS));

            var report = detector.analyze();
            assertFalse(report.hasIssues(), "the second task never waited for the first: " + report);
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("a task that waited behind the holder is found past a task that ran in between")
    void starvationIsFoundPastAnInterveningRun() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("intervening", true);
        try {
            detector.registerTimer(timer, TIMER);
            CountDownLatch lateRan = new CountDownLatch(1);
            // "early" falls due first, so the timer runs it between the holder and "late"; "late"
            // waited for the holder all the same, and must not be excused by the run before it.
            TimerTask early = quick(detector, timer, "early", new CountDownLatch(1));
            TimerTask late = quick(detector, timer, "late", lateRan);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    detector.recordTaskRun(timer, TIMER, this, "holder");
                    timer.schedule(early, 2);
                    timer.schedule(late, 4);
                    holdUntilPast(late.scheduledExecutionTime());
                    detector.recordTaskComplete(timer, TIMER, "holder");
                }
            }, 0);
            assertTrue(lateRan.await(10, SECONDS));

            String report = detector.analyze().toString();
            assertTrue(report.contains("'late'"), "late fell due while the holder ran: " + report);
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("a periodic task running into its own next execution is not reported as starving another")
    void aPeriodicTaskOverrunningItselfIsSilent() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("self", true);
        try {
            detector.registerTimer(timer, TIMER);
            CountDownLatch twice = new CountDownLatch(2);
            AtomicInteger runs = new AtomicInteger();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    detector.recordTaskRun(timer, TIMER, this, "tick");
                    if (runs.incrementAndGet() == 1) {
                        holdUntilPast(scheduledExecutionTime() + 5); // the next execution falls due now
                    } else {
                        cancel();
                    }
                    detector.recordTaskComplete(timer, TIMER, "tick");
                    twice.countDown();
                }
            }, 0, 5);
            assertTrue(twice.await(10, SECONDS));

            var report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "the only task that waited is the one that held the thread, and a fixed-rate "
                            + "catch-up is not another task starved: " + report);
        } finally {
            timer.cancel();
        }
    }
}
