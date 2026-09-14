package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MissedSignalDetector}. Recordings here run on one thread; the
 * multi-thread cases, with real monitors, are in {@link MissedSignalDetectorAccuracyTest}.
 */
public class MissedSignalDetectorTest {

    @Test
    void testNotifyWithWaiterNoMissedSignal() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordWait("dataReady");      // thread enters wait
        detector.recordNotify("dataReady");    // signal while waiter is present
        detector.recordWakeup("dataReady");    // thread wakes up

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "notify() with a waiting thread should not report missed signal");
    }

    @Test
    void testNotifyWithNoWaiterAndNoLaterWaitIsNotAFinding() {
        MissedSignalDetector detector = new MissedSignalDetector();

        // Nobody waits, before or after: a predicate-guarded waiter that saw the flag. Nothing lost.
        detector.recordNotify("dataReady");
        detector.recordNotifyAll("workReady");

        assertFalse(detector.analyze().hasIssues(),
                "a notify with no waiter is also the correct flag-then-notify handshake (#586)");
    }

    @Test
    void testNotifyWithNoWaiterThenUnsignalledWaitIsMissedSignal() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordNotify("dataReady");   // nobody waiting: discarded
        detector.recordWait("dataReady");     // a wait begins afterwards
        detector.recordWakeup("dataReady");   // and ends with no notify in between

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "a wait that followed a lost notify and got none should be flagged");
        assertTrue(report.missedConditions.get(0).contains("dataReady"));
        assertTrue(report.missedConditions.get(0).contains("1 ended unsignalled, 0 still waiting"),
                report.missedConditions.get(0));
    }

    @Test
    void testNotifyAllWithNoWaiterThenWaitStillOpenIsMissedSignal() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordNotifyAll("workReady");
        detector.recordWait("workReady");     // never woken

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "a waiter still blocked after a lost notifyAll() should be flagged");
        assertTrue(report.missedConditions.get(0).contains("0 ended unsignalled, 1 still waiting"),
                report.missedConditions.get(0));
    }

    @Test
    void testWaitBeforeAnyNotifyIsNotAFinding() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordWait("cond");
        detector.recordWakeup("cond");    // timed out: no notify was ever sent, so none was lost
        detector.recordNotify("cond");    // nobody waits after it

        assertFalse(detector.analyze().hasIssues(),
                "no notify preceded the wait, so the wait cannot have missed one");
    }

    @Test
    void testMultipleWaitersOnlyOneLeaves() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordWait("q");
        detector.recordWait("q");   // 2 waiters

        detector.recordWakeup("q"); // 1 waiter remains

        detector.recordNotify("q"); // still 1 waiter → NOT lost

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertFalse(report.hasIssues(), "notify() when one waiter remains should not be flagged");
    }

    @Test
    void testUnmatchedWakeupIsIgnored() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordWakeup("q");  // no wait recorded
        detector.recordWakeup("q");
        detector.recordNotify("q");  // lost, but nobody waits afterwards

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testMultipleConditionsTrackedIndependently() {
        MissedSignalDetector detector = new MissedSignalDetector();

        // condA: a lost notify, but the later wait is signalled
        detector.recordNotify("condA");
        detector.recordWait("condA");
        detector.recordNotify("condA");
        detector.recordWakeup("condA");

        // condB: missed signal
        detector.recordNotify("condB");
        detector.recordWait("condB");
        detector.recordWakeup("condB");

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "condB should flag a missed signal");
        assertTrue(report.missedConditions.stream().anyMatch(s -> s.contains("condB")));
        assertFalse(report.missedConditions.stream().anyMatch(s -> s.contains("condA")),
                "condA's wait received a notify and should not be flagged");
    }

    @Test
    void testDistinctMonitorsNeverShareHistory() {
        MissedSignalDetector detector = new MissedSignalDetector();
        Object first = new Object();
        Object second = new Object();

        detector.recordNotify(first);   // lost on the first monitor
        detector.recordWait(second);    // an unsignalled wait on a different monitor
        detector.recordWakeup(second);

        assertFalse(detector.analyze().hasIssues(),
                "the lost notify was on another monitor; keyed by name both would read as one condition");

        detector.recordWait(first);
        detector.recordWakeup(first);

        MissedSignalDetector.MissedSignalReport report = detector.analyze();
        assertEquals(1, report.missedConditions.size(), report.toString());
        assertTrue(report.missedConditions.get(0).startsWith("Object@"), report.toString());
    }

    @Test
    void testMonitorAndSameNamedStringAreDistinctConditions() {
        MissedSignalDetector detector = new MissedSignalDetector();
        Object monitor = new Object();

        detector.recordNotify("ready");
        detector.recordWait(monitor);
        detector.recordWakeup(monitor);

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testCountsInTheFinding() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordWait("event");
        detector.recordNotify("event"); // delivered
        detector.recordWakeup("event");

        detector.recordNotify("event"); // lost
        detector.recordNotify("event"); // lost
        detector.recordWait("event");   // unsignalled
        detector.recordWakeup("event");

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertTrue(report.hasIssues());
        String entry = report.missedConditions.get(0);
        assertTrue(entry.contains("2 of 3"), "Should report 2 notifies with no waiter out of 3 total: " + entry);
    }

    @Test
    void testNullConditionNameIsIgnored() {
        MissedSignalDetector detector = new MissedSignalDetector();

        assertDoesNotThrow(() -> {
            detector.recordWait((String) null);
            detector.recordNotify((String) null);
            detector.recordWakeup((String) null);
            detector.recordNotifyAll((String) null);
            detector.recordWait((Object) null);
            detector.recordNotify((Object) null);
            detector.recordWakeup((Object) null);
            detector.recordNotifyAll((Object) null);
        });

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsKeywords() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.recordNotify("lostSignal");
        detector.recordWait("lostSignal");

        String text = detector.analyze().toString();

        assertNotNull(text);
        assertTrue(text.contains("MISSED SIGNAL"), "Should contain header");
        assertTrue(text.contains("lostSignal"), "Should name the condition");
        assertTrue(text.contains("Fix:"), "Should suggest a fix");
        assertTrue(text.contains("wait()"), "Should mention wait()");
    }
}
