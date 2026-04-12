package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Timer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TimerDetector}.
 */
public class TimerDetectorTest {

    @Test
    void testCleanTimerNoIssues() {
        TimerDetector detector = new TimerDetector();
        Timer timer = new Timer("test-timer");

        detector.registerTimer(timer, "clean-timer");
        detector.recordTaskSchedule(timer, "clean-timer", "task-1");
        detector.recordTaskRun(timer, "clean-timer", "task-1");
        detector.recordTaskComplete(timer, "clean-timer", "task-1");
        detector.recordTimerCancel(timer, "clean-timer");

        TimerDetector.TimerReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Clean timer usage should not report issues");
        timer.cancel();
    }

    @Test
    void testTaskExceptionDetected() {
        TimerDetector detector = new TimerDetector();
        Timer timer = new Timer("exception-timer");

        detector.registerTimer(timer, "exception-timer");
        detector.recordTaskSchedule(timer, "exception-timer", "failing-task");
        detector.recordTaskRun(timer, "exception-timer", "failing-task");
        detector.recordTaskException(timer, "exception-timer", "failing-task",
                new RuntimeException("boom"));

        TimerDetector.TimerReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Task exception should be detected");
        assertFalse(report.timerThreadFailures.isEmpty(), "Should report timer thread failure");
        timer.cancel();
    }

    @Test
    void testLongRunningTaskDetected() throws InterruptedException {
        TimerDetector detector = new TimerDetector();
        Timer timer = new Timer("long-task-timer");

        detector.registerTimer(timer, "long-timer");
        detector.recordTaskRun(timer, "long-timer", "slow-task");

        // Simulate long-running task by sleeping beyond the threshold
        Thread.sleep(150);

        detector.recordTaskComplete(timer, "long-timer", "slow-task");

        TimerDetector.TimerReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Long-running task should be detected");
        assertFalse(report.longRunningTaskWarnings.isEmpty(), "Should report long-running task warning");
        timer.cancel();
    }

    @Test
    void testTimerCancellationTracked() {
        TimerDetector detector = new TimerDetector();
        Timer timer = new Timer("cancel-timer");

        detector.registerTimer(timer, "cancel-timer");
        detector.recordTaskSchedule(timer, "cancel-timer", "task-1");
        detector.recordTimerCancel(timer, "cancel-timer");

        TimerDetector.TimerReport report = detector.analyze();

        assertNotNull(report);
        String activity = report.timerActivity.get("cancel-timer");
        assertNotNull(activity);
        assertTrue(activity.contains("cancelled: true"), "Should record cancellation");
        timer.cancel();
    }

    @Test
    void testNullSafety() {
        TimerDetector detector = new TimerDetector();

        assertDoesNotThrow(() -> {
            detector.registerTimer(null, "null-timer");
            detector.recordTaskSchedule(null, "null", "task");
            detector.recordTaskRun(null, "null", "task");
            detector.recordTaskComplete(null, "null", "task");
            detector.recordTaskException(null, "null", "task", null);
            detector.recordTimerCancel(null, "null");
        });

        TimerDetector.TimerReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        TimerDetector detector = new TimerDetector();
        Timer timer = new Timer("report-timer");

        detector.registerTimer(timer, "report-timer");
        detector.recordTaskSchedule(timer, "report-timer", "task-1");
        detector.recordTaskRun(timer, "report-timer", "task-1");
        detector.recordTaskException(timer, "report-timer", "task-1",
                new RuntimeException("test exception"));

        TimerDetector.TimerReport report = detector.analyze();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("TIMER ISSUES DETECTED"), "Should contain header");
        assertTrue(text.contains("Timer Thread Failures"), "Should describe timer thread failure");
        assertTrue(text.contains("ScheduledExecutorService"), "Should suggest fix");
        timer.cancel();
    }

    @Test
    void testMultipleTasksTracked() {
        TimerDetector detector = new TimerDetector();
        Timer timer = new Timer("multi-task-timer");

        detector.registerTimer(timer, "multi-timer");
        for (int i = 1; i <= 5; i++) {
            String taskName = "task-" + i;
            detector.recordTaskSchedule(timer, "multi-timer", taskName);
            detector.recordTaskRun(timer, "multi-timer", taskName);
            detector.recordTaskComplete(timer, "multi-timer", taskName);
        }

        TimerDetector.TimerReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Five quick tasks should not trigger issues");
        String activity = report.timerActivity.get("multi-timer");
        assertNotNull(activity);
        assertTrue(activity.contains("completed: 5"), "Should track completed count");
        timer.cancel();
    }
}
