package se.deversity.asynctest.diagnostics;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "The timer thread died" is a claim about the timer thread, so it is decided there (#567).
 *
 * <p>{@code recordTaskException} set the death flag unconditionally, which made the call the
 * finding: a task that catches its exception, records it and carries on is correct, the timer
 * keeps running, and it was reported as having silently cancelled every remaining task.
 */
class TimerDetectorAccuracyTest {

    @Test
    @DisplayName("an exception caught and recorded on the timer thread is not a thread death")
    void caughtExceptionIsNotADeath() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("caught", true);
        try {
            detector.registerTimer(timer, "caught");
            CountDownLatch handled = new CountDownLatch(1);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        throw new IllegalStateException("handled");
                    } catch (IllegalStateException e) {
                        detector.recordTaskException(timer, "caught", "handled", e);
                    }
                    handled.countDown();
                }
            }, 0);
            assertTrue(handled.await(5, TimeUnit.SECONDS));

            // Proves the premise: the timer still runs tasks after the exception.
            CountDownLatch stillAlive = new CountDownLatch(1);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    stillAlive.countDown();
                }
            }, 0);
            assertTrue(stillAlive.await(5, TimeUnit.SECONDS), "premise: the timer thread is alive");

            assertFalse(detector.analyze().hasIssues(),
                    "the exception was handled and the timer kept running: " + detector.analyze());
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("an exception that escapes a task, and really kills the timer thread, is reported")
    void escapedExceptionIsADeath() throws InterruptedException {
        var detector = new TimerDetector();
        Timer timer = new Timer("escaped", true);
        detector.registerTimer(timer, "escaped");
        CountDownLatch recorded = new CountDownLatch(1);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                IllegalStateException boom = new IllegalStateException("escaped");
                detector.recordTaskException(timer, "escaped", "boom", boom);
                recorded.countDown();
                throw boom;
            }
        }, 0);
        assertTrue(recorded.await(5, TimeUnit.SECONDS));

        assertTrue(detector.analyze().hasIssues(),
                "the task's exception escaped run() and took the timer thread with it");
    }
}
