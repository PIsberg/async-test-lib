package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class InterruptSwallowingDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new InterruptSwallowingDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenRestoredOnSameThread() {
        var d = new InterruptSwallowingDetector();
        d.recordCatch(Thread.currentThread(), "MyService.work:42", true);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsSwallowedInterrupt() {
        var d = new InterruptSwallowingDetector();
        d.recordCatch(Thread.currentThread(), "MyService.work:42", false);
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("MyService.work:42"));
    }

    @Test
    void testMixedRestoredAndSwallowed() throws Exception {
        var d = new InterruptSwallowingDetector();
        d.recordCatch(Thread.currentThread(), "TaskA:10", true);
        Thread t2 = new Thread(() -> d.recordCatch(Thread.currentThread(), "TaskB:20", false));
        t2.start(); t2.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("TaskB:20"));
    }

    @Test
    void testMultipleSwallowsReportedSeparately() throws Exception {
        var d = new InterruptSwallowingDetector();
        d.recordCatch(Thread.currentThread(), "A:1", false);
        Thread t2 = new Thread(() -> d.recordCatch(Thread.currentThread(), "B:2", false));
        t2.start(); t2.join();
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testNullSafety() {
        var d = new InterruptSwallowingDetector();
        assertDoesNotThrow(() -> d.recordCatch(null, "loc", false));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullLocation() {
        var d = new InterruptSwallowingDetector();
        d.recordCatch(Thread.currentThread(), null, false);
        var r = d.analyze();
        assertTrue(r.hasIssues());
        assertTrue(r.violations.get(0).contains("unknown"));
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new InterruptSwallowingDetector();
        d.recordCatch(Thread.currentThread(), "loc", false);
        String s = d.analyze().toString();
        assertTrue(s.contains("INTERRUPT SWALLOWING"));
        assertTrue(s.contains("Fix"));
    }
}
