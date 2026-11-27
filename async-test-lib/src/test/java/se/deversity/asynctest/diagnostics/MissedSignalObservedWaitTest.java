package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The observed path of {@link MissedSignalDetector} (#694): the calls the agent makes from a woven
 * {@code Object.wait}, {@code notify} and loop back-edge, with no record written by the body.
 *
 * <p>Recordings run on one thread, in the order the woven instructions would produce them. The
 * woven end of the same pair is {@code MissedSignalWeavingTest} in the agent module.
 */
class MissedSignalObservedWaitTest {

    private final Object monitor = new Object();

    @Test
    void anIfWaitAfterALostNotifyIsReported() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();

        detector.recordObservedNotify(monitor);          // nobody is waiting: lost
        assertTrue(detector.recordObservedWait(monitor)); // if (!ready) monitor.wait(50)
        detector.recordObservedWakeup(monitor);           // the timed wait ran out

        assertTrue(detector.analyze().hasIssues(),
                "no back-edge followed the wakeup, so the wait was not a loop's");
    }

    @Test
    void aLoopWaitAfterALostNotifyIsSilent() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();

        detector.recordObservedNotify(monitor);
        assertTrue(detector.recordObservedWait(monitor)); // while (!ready) monitor.wait(50)
        detector.recordObservedWakeup(monitor);
        detector.recordObservedLoopBackEdge();            // the jump back to the predicate

        assertFalse(detector.analyze().hasIssues(),
                "the loop re-tests the state the lost notify changed");
    }

    @Test
    void aBackEdgeDoesNotConfirmAWaitFromAnEarlierRound() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();
        detector.recordObservedNotify(monitor);
        detector.recordObservedWait(monitor);
        detector.recordObservedWakeup(monitor);

        detector.markInvocationStart();          // a pooled worker starts the next round
        detector.recordObservedLoopBackEdge();   // some other loop in that round

        assertTrue(detector.analyze().hasIssues(),
                "the round end settled the wait; a later round's loop is not its loop");
    }

    @Test
    void aBackEdgeDoesNotConfirmAWaitTheBodyRecorded() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();
        detector.recordNotify(monitor);
        detector.recordWait(monitor);            // undeclared, recorded by the body
        detector.recordWakeup(monitor);
        detector.recordObservedLoopBackEdge();

        assertTrue(detector.analyze().hasIssues(),
                "a recorded wait keeps the #656 reading: only predicate checks confirm it");
    }

    @Test
    void aWaitTheBodyAlreadyRecordedIsNotCountedTwice() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();
        detector.recordNotify(monitor);
        detector.recordObservedNotify(monitor);  // the woven notify() behind that record

        detector.recordWait(monitor, false);
        assertFalse(detector.recordObservedWait(monitor),
                "the body recorded this wait: its declaration stands and the woven call adds nothing");
        detector.recordWakeup(monitor);

        String finding = detector.analyze().toString();
        assertTrue(finding.contains("1 wait(s)"), finding);
        assertTrue(finding.contains("1 of 1 notify"), finding);
    }

    @Test
    void aGuardedRecordSilencesTheWovenWaitBehindIt() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();
        detector.recordObservedNotify(monitor);
        detector.recordWait(monitor, true);
        assertFalse(detector.recordObservedWait(monitor));
        detector.recordWakeup(monitor);

        assertFalse(detector.analyze().hasIssues(), "an explicit guarded flag is never overridden");
    }

    @Test
    void anObservedNotifyReachingAnObservedWaiterIsNotLost() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();
        detector.recordObservedWait(monitor);
        detector.recordObservedNotify(monitor);
        detector.recordObservedWakeup(monitor);

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void settledObservedWaitsAreNotRetained() {
        MissedSignalDetector detector = new MissedSignalDetector();
        for (int round = 0; round < 100; round++) {
            detector.markInvocationStart();
            detector.recordObservedNotify(monitor);
            detector.recordObservedWait(monitor);
            detector.recordObservedWakeup(monitor);
        }
        detector.markInvocationStart();

        assertEquals(0, detector.retainedWaits(), "every closed round folds into a count");
    }

    @Test
    void aWaitEndedByAnInterruptIsNotJudged() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();

        detector.recordObservedNotify(monitor);             // lost
        assertTrue(detector.recordObservedWait(monitor));   // while (!ready) monitor.wait()
        detector.recordObservedWaitInterrupted(monitor);    // InterruptedException leaves the loop

        assertFalse(detector.analyze().hasIssues(),
                "an interrupted wait was cancelled, not woken, so no back-edge was owed (#1501)");
        assertEquals(0, detector.retainedWaits(), "and nothing of it stays open");
    }

    @Test
    void aHandledTimeoutConfirmsTheWaitOnItsOwnMonitorOnly() {
        MissedSignalDetector detector = new MissedSignalDetector();
        Object other = new Object();
        detector.markInvocationStart();

        detector.recordObservedNotify(monitor);
        detector.recordObservedNotify(other);
        detector.recordObservedWait(monitor);    // while (!done) { if (!c.await(..)) return; }
        detector.recordObservedWakeup(monitor);
        detector.recordObservedTimeoutHandled(monitor);
        detector.recordObservedWait(other);      // if (!done) other.await(..); on the same thread
        detector.recordObservedWakeup(other);
        detector.recordObservedTimeoutHandled(null);

        MissedSignalDetector.MissedSignalReport report = detector.analyze();
        assertTrue(report.hasIssues(), "the if-guarded wait on the other monitor stays a finding");
        assertEquals(1, report.toString().split("SIGNAL LOST", -1).length - 1,
                "and only that one: the handled timeout confirmed its own monitor's wait (#1501): "
                        + report);
    }

    @Test
    void aNotifyLostInAnEarlierRoundDoesNotStrandAnObservedWait() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.markInvocationStart();
        detector.recordObservedNotify(monitor);  // round 1: the producer came first, nobody waited

        detector.markInvocationStart();
        detector.recordObservedWait(monitor);    // round 2: no notify of this round was lost
        detector.recordObservedWakeup(monitor);
        detector.markInvocationStart();

        assertFalse(detector.analyze().hasIssues(),
                "a lost notify from another round was not this wait's hand-off (#1501)");

        detector.recordObservedNotify(monitor);  // round 3: lost in the waiter's own round
        detector.recordObservedWait(monitor);
        detector.recordObservedWakeup(monitor);
        detector.markInvocationStart();
        assertTrue(detector.analyze().hasIssues(), "the same shape within one round is reported");
    }
}
