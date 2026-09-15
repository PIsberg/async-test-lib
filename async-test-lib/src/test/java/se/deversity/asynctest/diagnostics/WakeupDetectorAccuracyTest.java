package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both directions of {@link WakeupDetector}'s model against real monitors (#590): the finding is a
 * wait that returned with no notify accounting for it, after which the waiting thread went on
 * without waiting again, which is what an {@code if} guard instead of a {@code while} loop does.
 * A notify that finds nobody waiting, and a wakeup the caller merely labels unnotified, are not
 * findings on their own.
 */
class WakeupDetectorAccuracyTest {

    private static void runOn(String name, Runnable body) throws InterruptedException {
        Thread thread = new Thread(body, name);
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive(), name + " did not finish");
    }

    @Test
    @DisplayName("flag-then-notifyAll with nobody waiting is the correct handshake and stays silent")
    void flagThenNotifyAllWithNoWaiterStaysSilent() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        boolean[] ready = {false};

        runOn("producer", () -> {
            synchronized (monitor) {
                ready[0] = true;
                detector.recordNotify(monitor, true);
                monitor.notifyAll();
            }
        });
        runOn("consumer", () -> {
            synchronized (monitor) {
                while (!ready[0]) {   // finds the flag set and never waits
                    detector.recordWaitEnter(monitor);
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    detector.recordWaitExit(monitor, true);
                }
            }
        });

        var report = detector.analyzeWakeups();
        assertFalse(report.hasIssues(),
                "the producer set the flag before notifying and the consumer re-checked it, so "
                        + "nothing was lost. Report:\n" + report);
        assertEquals(1, report.monitorsWithLostNotifications.size(),
                "the notify that found no waiter is still described as context");
    }

    @Test
    @DisplayName("a while loop that waits again after an unsignalled return stays silent")
    void whileLoopThatWaitsAgainStaysSilent() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        boolean[] ready = {false};
        CountDownLatch timedOutOnce = new CountDownLatch(2);

        Thread consumer = new Thread(() -> {
            synchronized (monitor) {
                while (!ready[0]) {
                    detector.recordWaitEnter(monitor);
                    try {
                        monitor.wait(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    detector.recordWaitExit(monitor, ready[0]);
                    timedOutOnce.countDown();
                }
            }
        }, "looping-consumer");
        consumer.start();
        assertTrue(timedOutOnce.await(10, TimeUnit.SECONDS), "consumer never returned from wait");
        synchronized (monitor) {
            ready[0] = true;
            detector.recordNotify(monitor, true);
            monitor.notifyAll();
        }
        consumer.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(consumer.isAlive());

        var report = detector.analyzeWakeups();
        assertFalse(report.hasIssues(),
                "every unsignalled return was followed by another wait, which is the re-check "
                        + "that makes a spurious wakeup harmless. Report:\n" + report);
    }

    @Test
    @DisplayName("an if-guarded wait that returns with no notify and proceeds fires")
    void ifGuardedWaitThatProceedsFires() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        boolean[] ready = {false};
        boolean[] proceededUnready = {false};

        runOn("if-consumer", () -> {
            synchronized (monitor) {
                if (!ready[0]) {   // the bug: a wakeup is taken as the condition
                    detector.recordWaitEnter(monitor);
                    try {
                        monitor.wait(5);   // nobody notifies: returns as a spurious wakeup would
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    detector.recordWaitExit(monitor, ready[0]);
                }
                proceededUnready[0] = !ready[0];
            }
        });

        assertTrue(proceededUnready[0], "premise: the consumer went on with ready still false");
        var report = detector.analyzeWakeups();
        assertTrue(report.hasIssues(),
                "the consumer returned from wait() with no notify and never waited again. "
                        + "Report:\n" + report);
        assertEquals(1, report.monitorsWithSpuriousWakeups.size());
    }

    @Test
    @DisplayName("a wait exit from a thread that never waited changes nothing")
    void unmatchedWaitExitChangesNothing() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch strayDone = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            detector.recordWaitEnter(monitor);
            entered.countDown();
            try {
                strayDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "waiter");
        waiter.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        // A stray exit with no wait of its own: it used to count as a spurious wakeup and drive
        // the shared waiter count to zero under a live waiter, or below zero with none.
        runOn("stray", () -> detector.recordWaitExit(monitor, false));
        strayDone.countDown();
        waiter.join(TimeUnit.SECONDS.toMillis(10));

        detector.recordNotify(monitor, true);
        var report = detector.analyzeWakeups();
        assertFalse(report.hasIssues(), "a stray exit is not a wakeup. Report:\n" + report);
        assertTrue(report.monitorsWithLostNotifications.isEmpty(),
                "the waiter's wait is still open, so the notify found a waiter; the stray exit "
                        + "must not have closed it. Report:\n" + report);
    }

    @Test
    @DisplayName("a wait woken by a recorded notifyAll is accounted for whatever flag the caller passes")
    void recordedNotifyAccountsForTheWakeup() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            detector.recordWaitEnter(monitor);
            entered.countDown();
            try {
                notified.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            detector.recordWaitExit(monitor, false);   // the caller could not tell
        }, "waiter");
        waiter.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        detector.recordNotify(monitor, true);
        notified.countDown();
        waiter.join(TimeUnit.SECONDS.toMillis(10));

        var report = detector.analyzeWakeups();
        assertFalse(report.hasIssues(),
                "a notifyAll was recorded while the wait was open. Report:\n" + report);
    }

    @Test
    @DisplayName("one notify accounts for one waiter, so the second unsignalled return that proceeds fires")
    void singleNotifyAccountsForOneWaiter() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Runnable waiter = () -> {
            detector.recordWaitEnter(monitor);
            bothEntered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            detector.recordWaitExit(monitor, false);
        };
        Thread first = new Thread(waiter, "waiter-1");
        Thread second = new Thread(waiter, "waiter-2");
        first.start();
        second.start();
        assertTrue(bothEntered.await(10, TimeUnit.SECONDS));
        detector.recordNotify(monitor, false);   // wakes exactly one of them
        release.countDown();
        first.join(TimeUnit.SECONDS.toMillis(10));
        second.join(TimeUnit.SECONDS.toMillis(10));

        var report = detector.analyzeWakeups();
        assertTrue(report.hasIssues(),
                "one notify cannot account for two returns, and neither waiter waited again. "
                        + "Report:\n" + report);
    }

    @Test
    @DisplayName("a round boundary closes an unsignalled return, so the same pooled thread's next-round wait does not excuse it")
    void roundBoundaryClosesAPendingReturn() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();

        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);
        detector.markInvocationStart();
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, true);

        assertTrue(detector.analyzeWakeups().hasIssues(),
                "the first round's waiter went on without waiting again; the wait in the next "
                        + "round is a fresh body execution, not its re-check");
    }

    @Test
    @DisplayName("analysis does not consume state: analysing twice reports the same finding")
    void analysisIsRepeatable() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);

        assertTrue(detector.analyzeWakeups().hasIssues());
        assertTrue(detector.analyzeWakeups().hasIssues());
    }
}
