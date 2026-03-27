package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ForkJoinPoolDetector.
 */
public class ForkJoinPoolDetectorTest {

    @Test
    void testNormalForkJoinUsage() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);

        detector.registerPool(pool, "normalPool", 2);
        detector.recordFork(pool, "normalPool", "task1");
        detector.recordJoin(pool, "normalPool", "task1");
        detector.recordTaskTime(pool, "normalPool", 10L);

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testForkWithoutJoinDetection() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);

        detector.registerPool(pool, "forkNoJoinPool", 2);
        detector.recordFork(pool, "forkNoJoinPool", "task1");
        // Missing join!
        detector.recordForkWithoutJoin("forkNoJoinPool", "task1");

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect fork without join");
    }

    @Test
    void testExceptionInTaskDetection() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);

        detector.registerPool(pool, "exceptionPool", 2);
        detector.recordException("exceptionPool", "task1", new RuntimeException("test"));

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect exception in task");
    }

    @Test
    void testWorkStealingTracking() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(4);

        detector.registerPool(pool, "stealingPool", 4);

        // Simulate work stealing events
        detector.recordWorkSteal(pool);
        detector.recordWorkSteal(pool);
        detector.recordWorkSteal(pool);

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        // Work stealing is normal, not an issue
        assertFalse(report.hasIssues(), "Work stealing is normal behavior");
    }

    @Test
    void testMultiTaskForkJoin() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(4);

        detector.registerPool(pool, "multiTaskPool", 4);

        for (int i = 0; i < 10; i++) {
            detector.recordFork(pool, "multiTaskPool", "task" + i);
            detector.recordJoin(pool, "multiTaskPool", "task" + i);
            detector.recordTaskTime(pool, "multiTaskPool", 5L);
        }

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-task fork/join should work correctly");
    }

    @Test
    void testReportToString() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);

        detector.registerPool(pool, "testPool", 2);
        detector.recordForkWithoutJoin("testPool", "task1");

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("FORKJOINPOOL ISSUES DETECTED"), "Report should have header");
    }
}
