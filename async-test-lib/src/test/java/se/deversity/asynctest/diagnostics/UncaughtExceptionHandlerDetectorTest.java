package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UncaughtExceptionHandlerDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new UncaughtExceptionHandlerDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenThreadDoesNotThrow() {
        var d = new UncaughtExceptionHandlerDetector();
        Thread t = new Thread(() -> {});
        d.recordThreadStart(t);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenHandlerIsSet() {
        var d = new UncaughtExceptionHandlerDetector();
        Thread t = new Thread(() -> {});
        t.setUncaughtExceptionHandler((th, ex) -> {});
        d.recordThreadStart(t);
        d.recordUncaughtException(t, new RuntimeException("boom"));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenAJvmWideDefaultHandlerIsSet() {
        // #758: the report's own fix names Thread.setDefaultUncaughtExceptionHandler, and a thread
        // with no handler of its own dispatches to it through its ThreadGroup.
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {});
        try {
            var d = new UncaughtExceptionHandlerDetector();
            Thread t = new Thread(() -> {});
            d.recordThreadStart(t);
            d.recordUncaughtException(t, new RuntimeException("boom"));
            assertFalse(d.analyze().hasIssues());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void testDetectsUncaughtExceptionWhenNeitherHandlerNorDefaultIsSet() {
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(null);
        try {
            var d = new UncaughtExceptionHandlerDetector();
            Thread t = new Thread(() -> {});
            d.recordThreadStart(t);
            d.recordUncaughtException(t, new RuntimeException("boom"));
            assertTrue(d.analyze().hasIssues());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void testDetectsUncaughtExceptionWithNoHandler() {
        var d = new UncaughtExceptionHandlerDetector();
        Thread t = new Thread(() -> {});
        d.recordThreadStart(t);
        d.recordUncaughtException(t, new RuntimeException("boom"));
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("RuntimeException"));
    }

    @Test
    void testThreadNameIncludedInReport() {
        var d = new UncaughtExceptionHandlerDetector();
        Thread t = new Thread(() -> {});
        t.setName("worker-7");
        d.recordThreadStart(t);
        d.recordUncaughtException(t, new IllegalStateException("x"));
        assertTrue(d.analyze().violations.get(0).contains("worker-7"));
    }

    @Test
    void testMultipleThreadsMixed() {
        var d = new UncaughtExceptionHandlerDetector();
        Thread t1 = new Thread(() -> {});
        Thread t2 = new Thread(() -> {});
        t2.setUncaughtExceptionHandler((th, ex) -> {});
        d.recordThreadStart(t1);
        d.recordThreadStart(t2);
        d.recordUncaughtException(t1, new RuntimeException("a"));
        d.recordUncaughtException(t2, new RuntimeException("b"));
        assertEquals(1, d.analyze().violations.size()); // only t1 should be flagged
    }

    @Test
    void testNullSafety() {
        var d = new UncaughtExceptionHandlerDetector();
        assertDoesNotThrow(() -> d.recordThreadStart(null));
        assertDoesNotThrow(() -> d.recordUncaughtException(null, new RuntimeException()));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new UncaughtExceptionHandlerDetector();
        Thread t = new Thread(() -> {});
        d.recordThreadStart(t);
        d.recordUncaughtException(t, new RuntimeException("x"));
        String s = d.analyze().toString();
        assertTrue(s.contains("UNCAUGHT EXCEPTION HANDLER MISSING"));
        assertTrue(s.contains("Fix"));
    }
}
