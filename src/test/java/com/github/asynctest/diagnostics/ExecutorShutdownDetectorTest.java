package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class ExecutorShutdownDetectorTest {

    @Test
    void testNoIssuesWhenNotUsed() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testNoIssuesWhenShutdownAndAwaitTermination() throws Exception {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "clean-pool");
        ex.submit(() -> {});
        detector.recordTaskSubmitted(ex);
        ex.shutdown();
        ex.awaitTermination(1, TimeUnit.SECONDS);
        detector.recordShutdownCalled(ex, false);
        detector.recordAwaitTerminationCalled(ex);

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertFalse(report.hasIssues(), "Clean shutdown should report no issues");
        ex.shutdownNow();
    }

    @Test
    void testDetectsExecutorNotShutDown() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "leaking-pool");
        detector.recordTaskSubmitted(ex);
        // shutdown never called

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect missing shutdown");
        assertFalse(report.notShutDown.isEmpty(), "notShutDown list should contain the entry");
        assertTrue(report.notShutDown.get(0).contains("leaking-pool"));
        ex.shutdownNow();
    }

    @Test
    void testDetectsShutdownWithoutAwaitTermination() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "no-await-pool");
        detector.recordTaskSubmitted(ex);
        detector.recordShutdownCalled(ex, false);
        // awaitTermination never called

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect missing awaitTermination");
        assertFalse(report.noAwaitTermination.isEmpty());
        assertTrue(report.noAwaitTermination.get(0).contains("no-await-pool"));
        ex.shutdownNow();
    }

    @Test
    void testShutdownWithAwaitTerminationFlagInSingleCall() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "good-pool");
        detector.recordTaskSubmitted(ex);
        detector.recordShutdownCalled(ex, true); // convenience flag

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertFalse(report.hasIssues(), "Should be clean with convenience flag");
        ex.shutdownNow();
    }

    @Test
    void testAutoNameFromIdentityHash() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newCachedThreadPool();
        detector.recordExecutorCreated(ex, null); // no name
        detector.recordTaskSubmitted(ex);

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.notShutDown.get(0).startsWith("executor@"));
        ex.shutdownNow();
    }

    @Test
    void testNullSafety() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        assertDoesNotThrow(() -> {
            detector.recordExecutorCreated(null, "null-pool");
            detector.recordTaskSubmitted(null);
            detector.recordShutdownCalled(null, false);
            detector.recordAwaitTerminationCalled(null);
        });
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "hint-pool");
        detector.recordTaskSubmitted(ex);

        String str = detector.analyze().toString();
        assertTrue(str.contains("EXECUTOR SHUTDOWN ISSUES DETECTED"));
        assertTrue(str.contains("Fix"));
        ex.shutdownNow();
    }

    @Test
    void testNoIssueWhenNoTasksSubmitted() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "idle-pool");
        // no tasks submitted, no shutdown — still no issue since no tasks were submitted

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertFalse(report.hasIssues(), "No tasks submitted means no leak risk");
        ex.shutdownNow();
    }
}
