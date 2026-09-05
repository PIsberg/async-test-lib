package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FutureBlockingDetectorTest {

    private FutureBlockingDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FutureBlockingDetector();
    }

    @Test
    void noExecutorsReturnNoIssues() {
        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.starvationRisks.isEmpty());
    }

    @Test
    void normalTaskFlowNoStarvation() {
        Object executor = new Object();
        detector.registerExecutor(executor, "testPool", 2);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskCompleted(executor);
        detector.recordTaskCompleted(executor);

        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.starvationRisks.isEmpty());
    }

    @Test
    void starvationDetected() {
        Object executor = new Object();
        detector.registerExecutor(executor, "singleThreadPool", 1);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordBlockingWait(executor);

        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.starvationRisks.isEmpty());
    }

    @Test
    void nullExecutorHandled() {
        assertDoesNotThrow(() -> detector.recordBlockingWait(null));
    }

    @Test
    void disabledSkipsRecording() {
        Object executor = new Object();
        detector.registerExecutor(executor, "pool", 1);
        detector.disable();
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordBlockingWait(executor);

        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void reportToStringWithStarvation() {
        Object executor = new Object();
        detector.registerExecutor(executor, "starvingPool", 1);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordBlockingWait(executor);

        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        String str = report.toString();
        assertNotNull(str);
        assertTrue(str.contains("FUTURE BLOCKING ISSUES"));
    }

    @Test
    void reportHasIssuesFalseWhenSafe() {
        Object executor = new Object();
        detector.registerExecutor(executor, "safePool", 4);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskCompleted(executor);

        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void aBlockingWaitThatCompletedIsNotStarvationOnTheNextRound() {
        Object executor = new Object();
        detector.registerExecutor(executor, "pool", 2);
        for (int round = 0; round < 2; round++) {
            detector.recordTaskSubmitted(executor);
            detector.recordTaskSubmitted(executor);
            detector.recordTaskStarted(executor);
            detector.recordTaskStarted(executor);
            detector.recordBlockingWait(executor);
            detector.recordTaskCompleted(executor);
            detector.recordTaskCompleted(executor);
        }
        assertFalse(detector.analyze().hasIssues(),
            "Every task completed, nothing is queued; a completed wait must not be reported "
                + "as workers blocked while tasks remain queued");
    }
}
