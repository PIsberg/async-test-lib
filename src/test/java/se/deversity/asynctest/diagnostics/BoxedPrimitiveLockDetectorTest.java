package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BoxedPrimitiveLockDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new BoxedPrimitiveLockDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueForPlainObject() {
        var d = new BoxedPrimitiveLockDetector();
        d.recordLockAcquire(new Object(), Thread.currentThread(), "A:1");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueForNonCachedInteger() {
        var d = new BoxedPrimitiveLockDetector();
        // Integer.valueOf(200) is NOT cached (range is -128..127)
        Integer lock = Integer.valueOf(200);
        d.recordLockAcquire(lock, Thread.currentThread(), "A:1");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsCachedInteger() {
        var d = new BoxedPrimitiveLockDetector();
        Integer lock = 42; // cached
        d.recordLockAcquire(lock, Thread.currentThread(), "Service:10");
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("42"));
    }

    @Test
    void testDetectsCachedIntegerBoundary() {
        var d = new BoxedPrimitiveLockDetector();
        d.recordLockAcquire(Integer.valueOf(-128), Thread.currentThread(), "A:1");
        d.recordLockAcquire(Integer.valueOf(127),  Thread.currentThread(), "A:2");
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testDetectsBooleanTrue() {
        var d = new BoxedPrimitiveLockDetector();
        d.recordLockAcquire(Boolean.TRUE, Thread.currentThread(), "B:5");
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("Boolean"));
    }

    @Test
    void testDetectsBooleanFalse() {
        var d = new BoxedPrimitiveLockDetector();
        d.recordLockAcquire(Boolean.FALSE, Thread.currentThread(), "B:6");
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testDetectsCachedLong() {
        var d = new BoxedPrimitiveLockDetector();
        Long lock = 0L; // cached
        d.recordLockAcquire(lock, Thread.currentThread(), "C:7");
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("Long"));
    }

    @Test
    void testDetectsInternedString() {
        var d = new BoxedPrimitiveLockDetector();
        String lock = "LOCK"; // string literal — interned
        d.recordLockAcquire(lock, Thread.currentThread(), "D:8");
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("LOCK"));
    }

    @Test
    void testNullSafety() {
        var d = new BoxedPrimitiveLockDetector();
        assertDoesNotThrow(() -> d.recordLockAcquire(null, Thread.currentThread(), "loc"));
        assertDoesNotThrow(() -> d.recordLockAcquire(42, null, "loc"));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new BoxedPrimitiveLockDetector();
        d.recordLockAcquire(Boolean.TRUE, Thread.currentThread(), "loc");
        String s = d.analyze().toString();
        assertTrue(s.contains("BOXED PRIMITIVE LOCK"));
        assertTrue(s.contains("Fix"));
    }
}
