package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SynchronizedOnLiteralDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SynchronizedOnLiteralDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueForNonLiteralObject() {
        var d = new SynchronizedOnLiteralDetector();
        Object lock = new Object(); // private dedicated lock — not a literal
        d.recordMonitorAcquired(lock, Thread.currentThread(), "MyClass.method");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsInterpolatedStringLiteral() {
        var d = new SynchronizedOnLiteralDetector();
        String literal = "lock"; // compile-time constant — interned
        d.recordMonitorAcquired(literal, Thread.currentThread(), "MyClass.method");
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("\"lock\""));
    }

    @Test
    void testDetectsCachedInteger() {
        var d = new SynchronizedOnLiteralDetector();
        Integer cached = 42; // autoboxed, within [-128, 127]
        d.recordMonitorAcquired(cached, Thread.currentThread(), "Ctx");
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("Integer.valueOf(42)"));
    }

    @Test
    void testDetectsCachedLong() {
        var d = new SynchronizedOnLiteralDetector();
        Long cached = 0L; // within [-128, 127]
        d.recordMonitorAcquired(cached, Thread.currentThread(), "Ctx");
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("Long.valueOf(0)"));
    }

    @Test
    void testNoIssueForLargeBoxedInteger() {
        var d = new SynchronizedOnLiteralDetector();
        Integer large = 1000; // outside cache range — new object each time
        d.recordMonitorAcquired(large, Thread.currentThread(), "Ctx");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new SynchronizedOnLiteralDetector();
        assertDoesNotThrow(() -> {
            d.recordMonitorAcquired(null, Thread.currentThread(), "x");
            d.recordMonitorAcquired("lit", null, "x");
        });
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new SynchronizedOnLiteralDetector();
        d.recordMonitorAcquired("lock", Thread.currentThread(), "X");
        String s = d.analyze().toString();
        assertTrue(s.contains("SYNCHRONIZED ON LITERAL"));
        assertTrue(s.contains("Fix"));
        assertTrue(s.contains("private final Object lock"));
    }
}
