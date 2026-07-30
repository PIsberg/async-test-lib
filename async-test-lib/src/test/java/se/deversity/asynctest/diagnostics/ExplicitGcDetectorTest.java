package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ExplicitGcDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new ExplicitGcDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsSingleGcCall() {
        var d = new ExplicitGcDetector();
        d.recordGcInvocation(Thread.currentThread(), "CacheManager.evict:58");
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("CacheManager.evict:58"));
    }

    @Test
    void testDetectsMultipleGcCalls() throws Exception {
        var d = new ExplicitGcDetector();
        d.recordGcInvocation(Thread.currentThread(), "A:1");
        Thread t2 = new Thread(() -> d.recordGcInvocation(Thread.currentThread(), "B:2"));
        t2.start(); t2.join();
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testNullSafety() {
        var d = new ExplicitGcDetector();
        assertDoesNotThrow(() -> d.recordGcInvocation(null, "loc"));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullLocation() {
        var d = new ExplicitGcDetector();
        d.recordGcInvocation(Thread.currentThread(), null);
        var r = d.analyze();
        assertTrue(r.hasIssues());
        assertTrue(r.violations.get(0).contains("unknown"));
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new ExplicitGcDetector();
        d.recordGcInvocation(Thread.currentThread(), "loc");
        String s = d.analyze().toString();
        assertTrue(s.contains("EXPLICIT GC"));
        assertTrue(s.contains("Fix"));
    }
}
