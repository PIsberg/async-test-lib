package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SharedTimeZoneDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SharedTimeZoneDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSingleThread() {
        var d = new SharedTimeZoneDetector();
        Object tz = new Object();
        d.recordMutation(tz, "setRawOffset", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsMultiThreadMutation() throws Exception {
        var d = new SharedTimeZoneDetector();
        Object tz = new Object();
        d.recordMutation(tz, "setRawOffset", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordMutation(tz, "setID", Thread.currentThread()));
        t2.start(); t2.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("2"));
        assertTrue(report.violations.get(0).contains("observes sharing, not locks"));
    }

    @Test
    void testSeparateTimezonesPerThreadNoIssue() throws Exception {
        var d = new SharedTimeZoneDetector();
        Object tz1 = new Object();
        Object tz2 = new Object();
        d.recordMutation(tz1, "setRawOffset", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordMutation(tz2, "setRawOffset", Thread.currentThread()));
        t2.start(); t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new SharedTimeZoneDetector();
        assertDoesNotThrow(() -> d.recordMutation(null, "op", Thread.currentThread()));
        assertDoesNotThrow(() -> d.recordMutation(new Object(), "op", null));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new SharedTimeZoneDetector();
        Object tz = new Object();
        d.recordMutation(tz, "setRawOffset", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordMutation(tz, "setRawOffset", Thread.currentThread()));
        t2.start(); t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("SHARED TIMEZONE"));
        assertTrue(s.contains("Fix"));
    }
}
