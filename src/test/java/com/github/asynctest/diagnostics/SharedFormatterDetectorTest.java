package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Formatter;
import static org.junit.jupiter.api.Assertions.*;

public class SharedFormatterDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SharedFormatterDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSingleThread() {
        var d = new SharedFormatterDetector();
        Formatter fmt = new Formatter();
        d.recordAccess(fmt, "fmt", Thread.currentThread());
        d.recordAccess(fmt, "fmt", Thread.currentThread()); // same thread twice — no issue
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsMultiThreadAccess() throws Exception {
        var d = new SharedFormatterDetector();
        Formatter fmt = new Formatter();
        d.recordAccess(fmt, "fmt", Thread.currentThread());
        Thread other = new Thread(() -> d.recordAccess(fmt, "fmt", Thread.currentThread()));
        other.start();
        other.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("fmt"));
        assertTrue(d.analyze().violations.get(0).contains("2"));
    }

    @Test
    void testDetectsPrintWriterShared() throws Exception {
        var d = new SharedFormatterDetector();
        PrintWriter pw = new PrintWriter(new StringWriter());
        d.recordAccess(pw, "pw", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(pw, "pw", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueForSeparateFormatterPerThread() throws Exception {
        var d = new SharedFormatterDetector();
        Formatter fmt1 = new Formatter();
        Formatter fmt2 = new Formatter();
        d.recordAccess(fmt1, "fmt1", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(fmt2, "fmt2", Thread.currentThread()));
        t2.start();
        t2.join();
        assertFalse(d.analyze().hasIssues()); // each thread has its own instance
    }

    @Test
    void testAutoLabelFromClassName() throws Exception {
        var d = new SharedFormatterDetector();
        Formatter fmt = new Formatter();
        d.recordAccess(fmt, null, Thread.currentThread()); // no name provided
        Thread t2 = new Thread(() -> d.recordAccess(fmt, null, Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("Formatter"));
    }

    @Test
    void testNullSafety() {
        var d = new SharedFormatterDetector();
        assertDoesNotThrow(() -> {
            d.recordAccess(null, "x", Thread.currentThread());
            d.recordAccess(new Formatter(), "x", null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new SharedFormatterDetector();
        Formatter fmt = new Formatter();
        d.recordAccess(fmt, "fmt", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(fmt, "fmt", Thread.currentThread()));
        t2.start();
        t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("SHARED FORMATTER"));
        assertTrue(s.contains("Fix"));
    }
}
