package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MissedSignalDetector#recordLoopStart(Object)} and {@link MissedSignalDetector#recordLoopEnd(Object)}
 * (#669): a caller that marks its {@code while (!ready)} loops gives the detector the back-edge the
 * recorded calls do not carry.
 *
 * <p>Since #656 two shapes recorded the same calls as a loop and were silenced: an
 * {@code if (!ready) wait()} followed later by a check that finds {@code ready} true, and two
 * consecutive {@code if (!ready) wait()} blocks. Once a monitor has a marked loop, its marks decide:
 * a wait inside one is a loop's, and a wait outside every mark is an {@code if}'s, whatever checks
 * surround it. Each test records on real monitors, one thread at a time, in the order written.
 */
@DisplayName("MissedSignalDetector with marked loops (#669)")
class MissedSignalLoopMarkTest {

    private final MissedSignalDetector detector = new MissedSignalDetector();
    private final Object monitor = new Object();

    /** A notify with nobody waiting: the signal the waits below needed. */
    private void loseANotify() {
        detector.recordNotify(monitor);
    }

    /** A loop the class marks elsewhere on the same monitor, whose predicate already holds. */
    private void aMarkedLoopThatNeverWaits() {
        detector.recordLoopStart(monitor);
        detector.recordPredicateCheck(monitor, true);
        detector.recordLoopEnd(monitor);
    }

    /** {@code if (!ready) wait()}: a check that finds the predicate false, and one wait. */
    private void anIfWait() {
        detector.recordPredicateCheck(monitor, false);
        detector.recordWait(monitor);
        detector.recordWakeup(monitor);
    }

    @Test
    @DisplayName("a marked while loop after a lost notify, re-checked and exiting, stays silent")
    void aMarkedLoopThatExitsStaysSilent() {
        loseANotify();
        detector.recordLoopStart(monitor);
        detector.recordPredicateCheck(monitor, false);
        detector.recordWait(monitor);
        detector.recordWakeup(monitor);
        detector.recordPredicateCheck(monitor, true);
        detector.recordLoopEnd(monitor);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "a marked loop re-tests what a lost notify changed:\n" + report);
    }

    @Test
    @DisplayName("a marked loop that waits again and then gives up stays silent")
    void aMarkedLoopThatWaitsAgainStaysSilent() {
        loseANotify();
        detector.recordLoopStart(monitor);
        anIfWait();
        anIfWait();   // the back-edge, inside the mark
        detector.recordPredicateCheck(monitor, false);
        detector.recordLoopEnd(monitor);

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("#669: on a monitor with marked loops, if-wait then a later check that finds ready fires")
    void anIfWaitFollowedByASatisfiedCheckFiresOnceLoopsAreMarked() {
        aMarkedLoopThatNeverWaits();
        loseANotify();
        anIfWait();
        detector.recordPredicateCheck(monitor, true);   // later, unrelated: ready is true by now

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the wait is outside every marked loop, so the satisfied check after it is not a "
                        + "loop exit and the lost notify stranded it:\n" + report);
    }

    @Test
    @DisplayName("#669: on a monitor with marked loops, two consecutive if-waits fire")
    void twoConsecutiveIfWaitsFireOnceLoopsAreMarked() {
        aMarkedLoopThatNeverWaits();
        loseANotify();
        anIfWait();
        anIfWait();
        detector.recordPredicateCheck(monitor, false);   // later: still not ready, the body moves on

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the second if is not a back-edge: neither wait is inside a marked loop:\n" + report);
    }

    @Test
    @DisplayName("the same two shapes on a monitor with no marked loop keep the #656 reading (documented limit)")
    void withoutAMarkTheShapesStillReadAsALoop() {
        loseANotify();
        anIfWait();
        detector.recordPredicateCheck(monitor, true);

        assertFalse(detector.analyze().hasIssues(),
                "unmarked, the calls are identical to a loop exit; this pins the limit the marks "
                        + "exist to lift:\n" + detector.analyze());
    }

    @Test
    @DisplayName("two consecutive if-waits on a monitor with no marked loop read as a bounded poll (documented limit)")
    void withoutAMarkTwoIfsStillReadAsALoop() {
        loseANotify();
        anIfWait();
        anIfWait();
        detector.recordPredicateCheck(monitor, false);

        assertFalse(detector.analyze().hasIssues(),
                "unmarked, the second wait is a back-edge and the last check a poll giving up:\n"
                        + detector.analyze());
    }

    @Test
    @DisplayName("a mark is per thread: another thread's open loop does not guard this thread's if-wait")
    void aMarkOnAnotherThreadDoesNotGuardThisOne() throws InterruptedException {
        Thread looper = new Thread(() -> detector.recordLoopStart(monitor));   // left open
        looper.start();
        looper.join(10_000);
        loseANotify();
        anIfWait();

        assertTrue(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("an explicit guarded flag still wins over a mark")
    void anExplicitFlagWinsOverAMark() {
        aMarkedLoopThatNeverWaits();
        loseANotify();
        detector.recordWait(monitor, true);   // declared guarded, outside a mark
        detector.recordWakeup(monitor);

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("an end with no start, and null monitors, change nothing")
    void unmatchedEndAndNullAreIgnored() {
        assertDoesNotThrow(() -> {
            detector.recordLoopEnd(monitor);
            detector.recordLoopStart(null);
            detector.recordLoopEnd(null);
        });
        loseANotify();
        detector.recordLoopStart(monitor);
        detector.recordWait(monitor);
        detector.recordWakeup(monitor);
        detector.recordLoopEnd(monitor);

        assertFalse(detector.analyze().hasIssues(),
                "the stray end must not have closed the loop opened after it:\n" + detector.analyze());
    }
}
