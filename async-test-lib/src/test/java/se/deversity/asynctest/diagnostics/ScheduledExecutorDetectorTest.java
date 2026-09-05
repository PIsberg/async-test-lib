package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
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

    @Test
    void testRecordScheduleOnUnregisteredExecutorIsNoop() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        assertDoesNotThrow(() -> detector.recordSchedule(executor, "unregistered", "task1"),
                "Recording a schedule for an unregistered executor must be a no-op, not throw");
    }

    @Test
    void testRecordTaskStartOnUnregisteredExecutorIsNoop() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        assertDoesNotThrow(() -> detector.recordTaskStart(executor, "unregistered", "task1"),
                "Recording a task start for an unregistered executor must be a no-op, not throw");
    }

    @Test
    void testRecordTaskCompleteOnUnregisteredExecutorIsNoop() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        assertDoesNotThrow(() -> detector.recordTaskComplete(executor, "unregistered", "task1", 10L),
                "Recording a task completion for an unregistered executor must be a no-op, not throw");
    }

    @Test
    void testRecordScheduleIncrementsScheduledTasksCount() throws Exception {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "schedCountExecutor", 1);
        detector.recordSchedule(executor, "schedCountExecutor", "task1");
        detector.recordSchedule(executor, "schedCountExecutor", "task2");

        assertEquals(2, readExecutorInfoIntField(detector, executor, "scheduledTasks"),
                "Each recordSchedule call must increment scheduledTasks by exactly one");
    }

    @Test
    void testRecordTaskStartAndCompleteAdjustRunningTasksCount() throws Exception {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "runningCountExecutor", 1);
        detector.recordTaskStart(executor, "runningCountExecutor", "task1");
        detector.recordTaskStart(executor, "runningCountExecutor", "task2");
        detector.recordTaskStart(executor, "runningCountExecutor", "task3");

        assertEquals(3, readExecutorInfoIntField(detector, executor, "runningTasks"),
                "Each recordTaskStart call must increment runningTasks by exactly one");

        detector.recordTaskComplete(executor, "runningCountExecutor", "task1", 10L);

        assertEquals(2, readExecutorInfoIntField(detector, executor, "runningTasks"),
                "recordTaskComplete must decrement runningTasks by exactly one");
    }

    @Test
    void testLongRunningTaskBoundaryAtExactly1000msIsNotFlagged() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "boundaryExecutor1000", 1);
        detector.recordShutdown(executor);
        detector.recordTaskComplete(executor, "boundaryExecutor1000", "task1", 1000L);

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertFalse(report.toString().contains("Long Running Tasks"),
                "A task taking exactly 1000ms must not be flagged as long running");
    }

    @Test
    void testLongRunningTaskBoundaryAt1001msIsFlagged() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "boundaryExecutor1001", 1);
        detector.recordShutdown(executor);
        detector.recordTaskComplete(executor, "boundaryExecutor1001", "task1", 1001L);

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertTrue(report.toString().contains("Long Running Tasks"),
                "A task taking 1001ms must be flagged as long running");
    }

    @Test
    void testExceptionCountIsExactAndIncrements() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "exCountExecutor", 1);
        detector.recordShutdown(executor);
        detector.recordException(executor, "exCountExecutor");
        detector.recordException(executor, "exCountExecutor");

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertTrue(report.toString().contains("Exceptions in Scheduled Tasks: 2"),
                "Two recordException calls must produce an exact count of 2, not -2 or 0");
    }

    @Test
    void testExceptionsSectionAbsentWhenNoExceptions() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "noExExecutor", 1);
        detector.recordShutdown(executor);

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertFalse(report.toString().contains("Exceptions in Scheduled Tasks"),
                "No exceptions recorded must not print the exceptions section");
    }

    @Test
    void testNotShutDownSectionPresentWhenExecutorNotShutDown() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "notShutExecutor", 1);

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertTrue(report.toString().contains("Executors Not Shut Down:"),
                "An executor that was never shut down must be listed");
    }

    @Test
    void testNoIssuesMessageWhenClean() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "cleanExecutor", 1);
        detector.recordShutdown(executor);

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();
        String reportStr = report.toString();

        assertTrue(reportStr.contains("No ScheduledExecutorService issues detected."),
                "A fully clean executor must print the no-issues message");
        assertFalse(reportStr.contains("Executors Not Shut Down:"),
                "A shut-down executor must not appear in the not-shut-down section");
    }

    @Test
    void testNoIssuesMessageAbsentWhenIssuesExist() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        detector.registerExecutor(executor, "dirtyExecutor", 1);
        // Not shut down -> issue present

        ScheduledExecutorDetector.ScheduledExecutorReport report = detector.analyze();

        assertFalse(report.toString().contains("No ScheduledExecutorService issues detected."),
                "When issues are present, the no-issues message must not be printed");
    }

    private static int readExecutorInfoIntField(ScheduledExecutorDetector detector,
            ScheduledExecutorService executor, String fieldName) throws Exception {
        Field registryField = ScheduledExecutorDetector.class.getDeclaredField("executorRegistry");
        registryField.setAccessible(true);
        Map<?, ?> registry = (Map<?, ?>) registryField.get(detector);
        Object info = registry.get(executor);
        Field field = info.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(info);
    }

    @Test
    void nullExecutorIsIgnoredOnEveryRecordPath() {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        assertDoesNotThrow(() -> {
            detector.registerExecutor(null, "n", 1);
            detector.recordSchedule(null, "n", "task");
            detector.recordTaskStart(null, "n", "task");
            detector.recordTaskComplete(null, "n", "task", 1);
            detector.recordException(null, "n");
            detector.recordShutdown(null);
            detector.analyze();
        });
    }

    @Test
    void exceptionsRecordedFromManyThreadsAreAllCounted() throws Exception {
        ScheduledExecutorDetector detector = new ScheduledExecutorDetector();
        java.util.concurrent.ScheduledExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            detector.registerExecutor(executor, "pool", 1);
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(8);
            Thread[] workers = new Thread[8];
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(() -> {
                    try { start.await(); } catch (Exception e) { throw new RuntimeException(e); }
                    for (int k = 0; k < 20_000; k++) detector.recordException(executor, "pool");
                });
                workers[i].start();
            }
            for (Thread w : workers) w.join();
            assertTrue(detector.analyze().toString().contains("Exceptions in Scheduled Tasks: 160000"),
                "a plain int counter incremented from worker threads loses updates: " + detector.analyze());
        } finally {
            executor.shutdownNow();
        }
    }
}
