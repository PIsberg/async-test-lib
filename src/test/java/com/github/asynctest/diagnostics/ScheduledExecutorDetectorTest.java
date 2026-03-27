package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScheduledExecutorDetector.
 */
public class ScheduledExecutorDetectorTest {

    @Test
    void testNormalExecutorUsage() throws Exception {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        detector.registerExecutor(executor, "normalExecutor", 2);
        detector.recordSchedule(executor, "normalExecutor", "task1");
        detector.recordTaskStart(executor, "normalExecutor", "task1");
        detector.recordTaskComplete(executor, "normalExecutor", "task1", 10L);
        detector.recordShutdown(executor);

        executor.shutdown();

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testMissingShutdownDetection() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        detector.registerExecutor(executor, "noShutdownExecutor", 2);
        detector.recordSchedule(executor, "noShutdownExecutor", "task1");
        // Missing shutdown!

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect missing shutdown");
    }

    @Test
    void testLongRunningTaskDetection() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        detector.registerExecutor(executor, "longTaskExecutor", 2);
        detector.recordSchedule(executor, "longTaskExecutor", "longTask");
        detector.recordTaskStart(executor, "longTaskExecutor", "longTask");
        detector.recordTaskComplete(executor, "longTaskExecutor", "longTask", 2000L);  // 2 seconds

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect long running task");
    }

    @Test
    void testExceptionInTaskDetection() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        detector.registerExecutor(executor, "exceptionExecutor", 2);
        detector.recordException(executor, "exceptionExecutor");

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect exception in task");
    }

    @Test
    void testMultiTaskScheduling() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

        detector.registerExecutor(executor, "multiTaskExecutor", 4);

        for (int i = 0; i < 5; i++) {
            detector.recordSchedule(executor, "multiTaskExecutor", "task" + i);
            detector.recordTaskStart(executor, "multiTaskExecutor", "task" + i);
            detector.recordTaskComplete(executor, "multiTaskExecutor", "task" + i, 10L);
        }

        detector.recordShutdown(executor);

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-task scheduling should work correctly");
    }

    @Test
    void testReportToString() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        detector.registerExecutor(executor, "testExecutor", 2);
        detector.recordException(executor, "testExecutor");

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("SCHEDULED EXECUTOR ISSUES DETECTED"), "Report should have header");
    }
}
