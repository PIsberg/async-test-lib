package se.deversity.asynctest.diagnostics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    @Test
    void testDetectsOptionalAsValueBased() {
        var d = new BoxedPrimitiveLockDetector();
        Optional<String> lock = Optional.of("x");
        d.recordLockAcquire(lock, Thread.currentThread(), "E:1");
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("value-based"));
        assertTrue(report.violations.get(0).contains("Optional"));
    }

    @Test
    void testDetectsInstantAsValueBased() {
        var d = new BoxedPrimitiveLockDetector();
        Instant lock = Instant.now();
        d.recordLockAcquire(lock, Thread.currentThread(), "E:2");
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("value-based"));
        assertTrue(report.violations.get(0).contains("Instant"));
    }

    @Test
    void testDetectsDurationAsValueBased() {
        var d = new BoxedPrimitiveLockDetector();
        Duration lock = Duration.ofSeconds(5);
        d.recordLockAcquire(lock, Thread.currentThread(), "E:3");
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("value-based"));
        assertTrue(report.violations.get(0).contains("Duration"));
    }

    @Test
    void testDetectsProcessHandleAsValueBased() {
        var d = new BoxedPrimitiveLockDetector();
        ProcessHandle lock = ProcessHandle.current();
        d.recordLockAcquire(lock, Thread.currentThread(), "E:4");
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("value-based"));
        assertTrue(report.violations.get(0).contains("ProcessHandle"));
    }

    @Test
    void testDetectsImmutableListAsValueBased() {
        var d = new BoxedPrimitiveLockDetector();
        List<Integer> lock = List.of(1, 2, 3); // java.util.ImmutableCollections$...
        d.recordLockAcquire(lock, Thread.currentThread(), "E:5");
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("value-based"));
    }

    @Test
    void testNoIssueForMutableArrayList() {
        var d = new BoxedPrimitiveLockDetector();
        // A regular mutable ArrayList is not value-based and must remain unflagged.
        d.recordLockAcquire(new java.util.ArrayList<Integer>(), Thread.currentThread(), "F:1");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testValueBasedReportIncludesValueBasedFixHint() {
        var d = new BoxedPrimitiveLockDetector();
        d.recordLockAcquire(Optional.empty(), Thread.currentThread(), "G:1");
        String s = d.analyze().toString();
        assertTrue(s.contains("value-based"));
        assertTrue(s.contains("Fix (value-based classes)"));
    }

    @Test
    void testMixedBoxedAndValueBasedViolations() {
        var d = new BoxedPrimitiveLockDetector();
        d.recordLockAcquire(Boolean.TRUE, Thread.currentThread(), "H:1");
        d.recordLockAcquire(Instant.now(), Thread.currentThread(), "H:2");
        var report = d.analyze();
        assertEquals(2, report.violations.size());
        String s = report.toString();
        assertTrue(s.contains("Fix:"));
        assertTrue(s.contains("Fix (value-based classes)"));
    }
}
