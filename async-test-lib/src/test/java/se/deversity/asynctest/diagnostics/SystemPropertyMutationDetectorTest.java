package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SystemPropertyMutationDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SystemPropertyMutationDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSingleThreadSets() {
        var d = new SystemPropertyMutationDetector();
        d.recordSet("my.key", "value", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsConcurrentSet() throws Exception {
        var d = new SystemPropertyMutationDetector();
        d.recordSet("shared.key", "v1", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordSet("shared.key", "v2", Thread.currentThread()));
        t2.start(); t2.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("shared.key"));
        assertTrue(report.violations.get(0).contains("2"));
    }

    @Test
    void testDetectsConcurrentSetAndClear() throws Exception {
        var d = new SystemPropertyMutationDetector();
        d.recordSet("key", "v", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordClear("key", Thread.currentThread()));
        t2.start(); t2.join();
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testDifferentKeysFromDifferentThreadsNoIssue() throws Exception {
        var d = new SystemPropertyMutationDetector();
        d.recordSet("key.a", "1", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordSet("key.b", "2", Thread.currentThread()));
        t2.start(); t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testSingleThreadMutationReportedAsWarning() {
        var d = new SystemPropertyMutationDetector();
        d.recordSet("solo.key", "val", Thread.currentThread());
        var report = d.analyze();
        assertFalse(report.hasIssues());
        assertFalse(report.singleThreadMutations.isEmpty());
    }

    @Test
    void testNullSafety() {
        var d = new SystemPropertyMutationDetector();
        assertDoesNotThrow(() -> d.recordSet(null, "v", Thread.currentThread()));
        assertDoesNotThrow(() -> d.recordSet("k", "v", null));
        assertDoesNotThrow(() -> d.recordClear(null, Thread.currentThread()));
        assertDoesNotThrow(() -> d.recordClear("k", null));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new SystemPropertyMutationDetector();
        d.recordSet("k", "v", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordSet("k", "x", Thread.currentThread()));
        t2.start(); t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("SYSTEM PROPERTY MUTATION"));
        assertTrue(s.contains("Fix"));
    }
}
