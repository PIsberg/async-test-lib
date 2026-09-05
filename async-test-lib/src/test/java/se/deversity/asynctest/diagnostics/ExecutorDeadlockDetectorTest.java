package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorDeadlockDetectorTest {

    private ExecutorDeadlockDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ExecutorDeadlockDetector();
    }

    @Test
    void noExecutorsReturnNoIssues() {
        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.selfDeadlocks.isEmpty());
    }

    @Test
    void normalTaskFlowNoDeadlock() {
        Object executor = new Object();
        detector.registerExecutor(executor, "testPool", 2);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskCompleted(executor);
        detector.recordTaskCompleted(executor);

        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.selfDeadlocks.isEmpty());
    }

    @Test
    void selfDeadlockDetected() {
        Object executor = new Object();
        detector.registerExecutor(executor, "singleThreadPool", 1);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordWaitingOnSibling(executor);

        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.selfDeadlocks.isEmpty());
    }

    @Test
    void nullExecutorHandledGracefully() {
        assertDoesNotThrow(() -> detector.recordTaskSubmitted(null));
    }

    @Test
    void reportToStringWithDeadlock() {
        Object executor = new Object();
        detector.registerExecutor(executor, "deadlockPool", 1);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordWaitingOnSibling(executor);

        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        String str = report.toString();
        assertNotNull(str);
        assertTrue(str.contains("EXECUTOR SELF-DEADLOCK"));
    }

    @Test
    void reportHasIssuesFalseWhenNoDeadlock() {
        Object executor = new Object();
        detector.registerExecutor(executor, "safePool", 4);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskCompleted(executor);

        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void multipleExecutorsTracked() {
        Object exec1 = new Object();
        Object exec2 = new Object();
        detector.registerExecutor(exec1, "pool1", 2);
        detector.registerExecutor(exec2, "pool2", 2);

        detector.recordTaskSubmitted(exec1);
        detector.recordTaskStarted(exec1);
        detector.recordTaskCompleted(exec1);

        detector.recordTaskSubmitted(exec2);
        detector.recordTaskStarted(exec2);
        detector.recordTaskCompleted(exec2);

        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void aSiblingWaitThatCompletedIsNotADeadlockOnTheNextRound() {
        Object executor = new Object();
        detector.registerExecutor(executor, "pool", 2);
        for (int round = 0; round < 2; round++) {
            detector.recordTaskSubmitted(executor);
            detector.recordTaskSubmitted(executor);
            detector.recordTaskStarted(executor);
            detector.recordTaskStarted(executor);
            detector.recordWaitingOnSibling(executor);
            detector.recordTaskCompleted(executor);
            detector.recordTaskCompleted(executor);
        }
        assertFalse(detector.analyze().hasIssues(),
            "Every task completed, nothing is queued; 'queued' was submitted minus running and "
                + "never drained on completion, so a finished wait counted forever");
    }
}
