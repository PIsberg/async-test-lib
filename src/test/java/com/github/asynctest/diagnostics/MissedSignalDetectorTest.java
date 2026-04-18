package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MissedSignalDetector}.
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
    void testNotifyWithNoWaiterIsMissedSignal() {
        MissedSignalDetector detector = new MissedSignalDetector();

        // No thread is waiting — signal is missed
        detector.recordNotify("dataReady");

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "notify() with zero waiters should be flagged");
        assertFalse(report.missedConditions.isEmpty());
        assertTrue(report.missedConditions.get(0).contains("dataReady"));
    }

    @Test
    void testNotifyAllWithNoWaiterIsMissedSignal() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordNotifyAll("workReady");

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "notifyAll() with zero waiters should be flagged");
        assertTrue(report.missedConditions.get(0).contains("workReady"));
    }

    @Test
    void testWaiterLeavesBeforeNotify() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordWait("cond");
        detector.recordWakeup("cond");    // spurious wakeup — waiter leaves
        detector.recordNotify("cond");    // now zero waiters → missed signal

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "notify() after waiter already left should be flagged");
    }

    @Test
    void testMultipleWaitersOnlyOneLeaves() {
        MissedSignalDetector detector = new MissedSignalDetector();

        detector.recordWait("q");
        detector.recordWait("q");   // 2 waiters

        detector.recordWakeup("q"); // 1 waiter remains

        detector.recordNotify("q"); // still 1 waiter → NOT missed

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertFalse(report.hasIssues(), "notify() when one waiter remains should not be flagged");
    }

    @Test
    void testMultipleConditionsTrackedIndependently() {
        MissedSignalDetector detector = new MissedSignalDetector();

        // condA: proper usage
        detector.recordWait("condA");
        detector.recordNotify("condA");

        // condB: missed signal
        detector.recordNotify("condB");

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "condB should flag a missed signal");
        assertTrue(report.missedConditions.stream().anyMatch(s -> s.contains("condB")));
        assertFalse(report.missedConditions.stream().anyMatch(s -> s.contains("condA")),
                "condA used correctly and should not be flagged");
    }

    @Test
    void testMixedSignalsSomeCorrectSomeMissed() {
        MissedSignalDetector detector = new MissedSignalDetector();

        // 3 notifies: first has waiter, next two are missed
        detector.recordWait("event");
        detector.recordNotify("event"); // OK — waiter present
        detector.recordWakeup("event"); // waiter leaves after being notified

        detector.recordNotify("event"); // missed — 0 waiters now
        detector.recordNotify("event"); // missed — 0 waiters

        MissedSignalDetector.MissedSignalReport report = detector.analyze();

        assertTrue(report.hasIssues());
        String entry = report.missedConditions.get(0);
        assertTrue(entry.contains("2 of 3"), "Should report 2 missed out of 3 total");
    }

    @Test
    void testNullConditionNameIsIgnored() {
        MissedSignalDetector detector = new MissedSignalDetector();

        assertDoesNotThrow(() -> {
            detector.recordWait(null);
            detector.recordNotify(null);
            detector.recordWakeup(null);
        });

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsKeywords() {
        MissedSignalDetector detector = new MissedSignalDetector();
        detector.recordNotify("lostSignal");

        String text = detector.analyze().toString();

        assertNotNull(text);
        assertTrue(text.contains("MISSED SIGNAL"), "Should contain header");
        assertTrue(text.contains("Fix:"), "Should suggest a fix");
        assertTrue(text.contains("wait()"), "Should mention wait()");
    }
}
