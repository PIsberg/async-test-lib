package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class FutureIgnoredDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new FutureIgnoredDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenInspected() throws Exception {
        var d = new FutureIgnoredDetector();
        Future<Void> f = CompletableFuture.completedFuture(null);
        d.recordSubmit(f, "task", Thread.currentThread());
        d.recordInspect(f, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsUninspectedFuture() {
        var d = new FutureIgnoredDetector();
        Future<Void> f = CompletableFuture.completedFuture(null);
        d.recordSubmit(f, "backgroundTask", Thread.currentThread());
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("backgroundTask"));
    }

    @Test
    void testMultipleFuturesMixed() {
        var d = new FutureIgnoredDetector();
        Future<Void> f1 = CompletableFuture.completedFuture(null);
        Future<Void> f2 = CompletableFuture.completedFuture(null);
        d.recordSubmit(f1, "task1", Thread.currentThread());
        d.recordSubmit(f2, "task2", Thread.currentThread());
        d.recordInspect(f1, Thread.currentThread());
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("task2"));
    }

    @Test
    void testInspectFromDifferentThread() throws Exception {
        var d = new FutureIgnoredDetector();
        Future<Void> f = CompletableFuture.completedFuture(null);
        d.recordSubmit(f, "crossTask", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordInspect(f, Thread.currentThread()));
        t2.start(); t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testAutoLabelWhenNameNull() {
        var d = new FutureIgnoredDetector();
        Future<Void> f = CompletableFuture.completedFuture(null);
        d.recordSubmit(f, null, Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("task@"));
    }

    @Test
    void testNullSafety() {
        var d = new FutureIgnoredDetector();
        assertDoesNotThrow(() -> d.recordSubmit(null, "t", Thread.currentThread()));
        assertDoesNotThrow(() -> d.recordSubmit(new Object(), "t", null));
        assertDoesNotThrow(() -> d.recordInspect(null, Thread.currentThread()));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new FutureIgnoredDetector();
        d.recordSubmit(new Object(), "t", Thread.currentThread());
        String s = d.analyze().toString();
        assertTrue(s.contains("IGNORED FUTURE"));
        assertTrue(s.contains("Fix"));
    }
}
