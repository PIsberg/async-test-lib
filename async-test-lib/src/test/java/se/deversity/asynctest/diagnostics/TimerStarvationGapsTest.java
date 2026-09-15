package se.deversity.asynctest.diagnostics;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two starvation shapes #575 left silent: tasks that fall due at the same instant (#614), and
 * fixed-delay repetitions, whose {@link TimerTask#scheduledExecutionTime()} is the instant the
 * timer picked them rather than when they fell due (#615).
 *
 * <p>Like {@link TimerStarvationModelTest}, every positive case makes the overlap certain: a task
 * that holds the thread keeps it until the wall clock is past the instant it has to cover, and
 * nothing depends on how fast the timer thread is scheduled.
 */
class TimerStarvationGapsTest {

    private static final String TIMER = "t";

    /** Keeps the calling thread busy until the wall clock is strictly past {@code instantMs}. */
    private static void holdUntilPast(long instantMs) {
        while (System.currentTimeMillis() <= instantMs) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** A task that records its run, holds the thread for a whole clock tick, and completes. */
    private static TimerTask holding(TimerDetector detector, Timer timer, String label, CountDownLatch done) {
        return new TimerTask() {
            @Override
            public void run() {
                detector.recordTaskRun(timer, TIMER, this, label);
                holdUntilPast(System.currentTimeMillis() + 1);
                detector.recordTaskComplete(timer, TIMER, label);
                done.countDown();
            }
        };
    }

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

    // ---- #614: tasks due at the same instant ---------------------------------------------------

    @Test
    @DisplayName("of two tasks due at the same instant, the one that waited out the other's run is reported")
    void coDueTasksBehindAHeldThreadAreReported() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("co-due", true);
        try {
            detector.registerTimer(timer, TIMER);
            CountDownLatch both = new CountDownLatch(2);
            Date due = new Date(System.currentTimeMillis() + 20);
            // Whichever the timer runs first, the second was already due when the first took the
            // thread, and the first keeps it for a whole tick.
            timer.schedule(holding(detector, timer, "first", both), due);
            timer.schedule(holding(detector, timer, "second", both), due);
            assertTrue(both.await(10, SECONDS));

            var report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "the later of two co-due tasks waited for the whole of the earlier one's run: "
                            + report);
            assertTrue(report.toString().contains("already due"),
                    "the report says the task was due before the other one took the thread: " + report);
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("two short tasks that share a due instant are silent")
    void coDueShortTasksAreSilent() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("co-due-short", true);
        try {
            detector.registerTimer(timer, TIMER);
            CountDownLatch both = new CountDownLatch(2);
            Date due = new Date(System.currentTimeMillis() + 20);
            timer.schedule(quick(detector, timer, "first", both), due);
            timer.schedule(quick(detector, timer, "second", both), due);
            assertTrue(both.await(10, SECONDS));

            var report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "neither task held the thread across a clock tick, so sharing a tick is "
                            + "sequencing, not starvation: " + report);
        } finally {
            timer.cancel();
        }
    }

    // ---- #615: fixed-delay repetitions ---------------------------------------------------------

    @Test
    @DisplayName("scheduledExecutionTime of a fixed-delay execution is the instant it was picked")
    void fixedDelayScheduledExecutionTimeIsThePickInstant() throws InterruptedException {
        // The premise #615 rests on, asked of the JDK rather than read from its source: a
        // fixed-delay execution that waited reports the instant it started, not when it fell due.
        Timer timer = new Timer("premise", true);
        try {
            AtomicLong firstPick = new AtomicLong();
            AtomicLong secondScheduled = new AtomicLong();
            AtomicLong secondStarted = new AtomicLong();
            CountDownLatch twice = new CountDownLatch(2);
            AtomicInteger runs = new AtomicInteger();
            TimerTask[] holder = new TimerTask[1];
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (runs.incrementAndGet() == 1) {
                        firstPick.set(scheduledExecutionTime());
                        holder[0] = new TimerTask() {
                            @Override
                            public void run() {
                                holdUntilPast(firstPick.get() + 5 + 2);
                            }
                        };
                        timer.schedule(holder[0], new Date(firstPick.get() + 1));
                    } else {
                        secondStarted.set(System.currentTimeMillis());
                        secondScheduled.set(scheduledExecutionTime());
                        cancel();
                    }
                    twice.countDown();
                }
            }, 0, 5);
            assertTrue(twice.await(10, SECONDS));

            assertTrue(secondScheduled.get() > firstPick.get() + 5,
                    "the second execution fell due at " + (firstPick.get() + 5) + " and waited for the "
                            + "holder, yet reports " + secondScheduled.get() + " (started "
                            + secondStarted.get() + ")");
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("a fixed-delay repetition that fell due while another task held the thread is reported")
    void aFixedDelayRepetitionThatWaitedIsReported() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("fixed-delay", true);
        try {
            detector.registerTimer(timer, TIMER);
            AtomicLong firstPick = new AtomicLong();
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch secondRan = new CountDownLatch(1);
            TimerTask holder = new TimerTask() {
                @Override
                public void run() {
                    detector.recordTaskRun(timer, TIMER, this, "holder");
                    long started = System.currentTimeMillis();
                    holdUntilPast(Math.max(firstPick.get() + 5, started + 1));
                    detector.recordTaskComplete(timer, TIMER, "holder");
                }
            };
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    detector.recordFixedDelayTaskRun(timer, TIMER, this, 5, "poller");
                    if (runs.incrementAndGet() == 1) {
                        firstPick.set(scheduledExecutionTime());
                        // Due before the poller's next execution, so the timer runs it first.
                        timer.schedule(holder, new Date(firstPick.get() + 1));
                    } else {
                        cancel();
                        secondRan.countDown();
                    }
                    detector.recordTaskComplete(timer, TIMER, "poller");
                }
            }, 0, 5);
            assertTrue(secondRan.await(10, SECONDS));

            var report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "the poller's second execution fell due while the holder had the thread: " + report);
            assertTrue(report.toString().contains("'poller'") && report.toString().contains("'holder'"),
                    "the report names the waiting poller and the holder: " + report);
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("a fixed-delay task alone on its timer is silent")
    void aFixedDelayTaskAloneIsSilent() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("fixed-delay-alone", true);
        try {
            detector.registerTimer(timer, TIMER);
            CountDownLatch thrice = new CountDownLatch(3);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    detector.recordFixedDelayTaskRun(timer, TIMER, this, 2, "poller");
                    detector.recordTaskComplete(timer, TIMER, "poller");
                    thrice.countDown();
                    if (thrice.getCount() == 0) {
                        cancel();
                    }
                }
            }, 0, 2);
            assertTrue(thrice.await(10, SECONDS));

            var report = detector.analyze();
            assertFalse(report.hasIssues(), "no other task ever held the thread: " + report);
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("a fixed-delay task whose run outlasts its own period starves nobody")
    void aFixedDelayTaskOverrunningItselfIsSilent() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("fixed-delay-self", true);
        try {
            detector.registerTimer(timer, TIMER);
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch twice = new CountDownLatch(2);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    detector.recordFixedDelayTaskRun(timer, TIMER, this, 5, "slow-poller");
                    if (runs.incrementAndGet() == 1) {
                        holdUntilPast(scheduledExecutionTime() + 5 + 2);
                    } else {
                        cancel();
                    }
                    detector.recordTaskComplete(timer, TIMER, "slow-poller");
                    twice.countDown();
                }
            }, 0, 5);
            assertTrue(twice.await(10, SECONDS));

            var report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "the only task the poller waited for was its own previous run: " + report);
        } finally {
            timer.cancel();
        }
    }
}
