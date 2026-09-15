package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A timed wait whose caller gives up at its deadline (#607). The unsignalled return and the absence
 * of a second wait look exactly like an {@code if} guard proceeding, so the give-up branch itself is
 * recorded with {@link WakeupDetector#recordGaveUp(Object)}. Only that branch closes the return: a
 * timed wait whose thread goes on after the return still fires.
 */
class WakeupDetectorDeadlineTest {

    private static void runOn(String name, Runnable body) throws InterruptedException {
        Thread thread = new Thread(body, name);
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive(), name + " did not finish");
    }

    /** The loop from #607: re-checks the flag, and returns false once the deadline has passed. */
    private static boolean awaitReady(WakeupDetector detector, Object monitor, boolean[] ready,
            long timeoutNanos) throws InterruptedException {
        synchronized (monitor) {
            long deadline = System.nanoTime() + timeoutNanos;
            while (!ready[0]) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    detector.recordGaveUp(monitor);
                    return false;   // gives up by design: correct
                }
                detector.recordWaitEnter(monitor);
                monitor.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                detector.recordWaitExit(monitor, ready[0]);
            }
            return true;
        }
    }

    @Test
    @DisplayName("a deadline loop that gives up after its last timed wait stays silent")
    void deadlineLoopThatGivesUpStaysSilent() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        boolean[] ready = {false};
        boolean[] result = {true};

        runOn("deadline-consumer", () -> {
            try {
                result[0] = awaitReady(detector, monitor, ready, TimeUnit.MILLISECONDS.toNanos(20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertFalse(result[0], "premise: nobody set the flag, so the loop gave up");
        var report = detector.analyzeWakeups();
        assertFalse(report.hasIssues(),
                "the last return was unsignalled and no wait followed, but the thread gave up at "
                        + "its deadline instead of acting on the condition. Report:\n" + report);
        assertEquals(1, report.monitorsWithDeadlineGiveUps.size(),
                "the give-up is still described as context. Report:\n" + report);
    }

    @Test
    @DisplayName("a timed if-guarded wait whose thread goes on after the timeout still fires")
    void timedIfGuardThatProceedsStillFires() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        boolean[] ready = {false};
        boolean[] proceededUnready = {false};

        runOn("timed-if-consumer", () -> {
            synchronized (monitor) {
                if (!ready[0]) {   // the bug, with a timeout: the time running out is taken as the condition
                    detector.recordWaitEnter(monitor);
                    try {
                        monitor.wait(5);
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
                "a timed wait ran out and the thread proceeded; no give-up was recorded, so the "
                        + "timeout does not excuse it. Report:\n" + report);
    }

    @Test
    @DisplayName("a give-up recorded by another thread does not excuse the waiter that proceeded")
    void giveUpFromAnotherThreadDoesNotExcuseTheWaiter() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();

        runOn("proceeding-waiter", () -> {
            detector.recordWaitEnter(monitor);
            detector.recordWaitExit(monitor, false);
        });
        runOn("other-thread", () -> detector.recordGaveUp(monitor));

        assertTrue(detector.analyzeWakeups().hasIssues(),
                "only the thread whose wait returned can say it gave up");
    }

    @Test
    @DisplayName("a give-up recorded before any return is not carried over to a later return that proceeds")
    void giveUpWithNothingPendingIsNotCarriedOver() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();

        detector.recordGaveUp(monitor);             // nothing to close yet
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);    // then an unsignalled return that proceeds

        var report = detector.analyzeWakeups();
        assertTrue(report.hasIssues(), "an earlier give-up closes nothing later. Report:\n" + report);
        assertEquals(1, report.monitorsWithSpuriousWakeups.size());
    }

    @Test
    @DisplayName("a give-up in the next round does not close the return a previous round left")
    void giveUpDoesNotCrossTheRoundBoundary() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();

        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);
        detector.markInvocationStart();
        detector.recordGaveUp(monitor);

        assertTrue(detector.analyzeWakeups().hasIssues(),
                "the first round's thread went on; the same pooled thread's give-up in the next "
                        + "round belongs to a different body execution");
    }

    @Test
    @DisplayName("a give-up closes only the return on its own monitor")
    void giveUpClosesOnlyItsOwnMonitor() {
        WakeupDetector detector = new WakeupDetector();
        Object gaveUpOn = new Object();
        Object proceededOn = new Object();

        detector.recordWaitEnter(gaveUpOn);
        detector.recordWaitExit(gaveUpOn, false);
        detector.recordGaveUp(gaveUpOn);
        detector.recordWaitEnter(proceededOn);
        detector.recordWaitExit(proceededOn, false);

        var report = detector.analyzeWakeups();
        assertEquals(1, report.monitorsWithSpuriousWakeups.size(),
                "only the monitor nobody gave up on is reported. Report:\n" + report);
    }
}
