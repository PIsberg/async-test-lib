package com.github.asynctest;

import com.github.asynctest.diagnostics.ExecutorDeadlockDetector;
import com.github.asynctest.diagnostics.FutureBlockingDetector;
import com.github.asynctest.diagnostics.LatchMisuseDetector;
import com.github.asynctest.diagnostics.LazyInitValidator;
import com.github.asynctest.diagnostics.NotifyAllValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyAsyncDetectorsTest {

    @Test
    void notifyAllValidatorDetectsSingleNotifyWithMultipleWaiters() {
        NotifyAllValidator validator = new NotifyAllValidator();
        Object monitor = new Object();

        validator.recordWaiterAdded(monitor, "legacyMonitor");
        validator.recordWaiterAdded(monitor, "legacyMonitor");
        validator.recordNotify(monitor, false);

        NotifyAllValidator.NotifyAllReport report = validator.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.notifyInsteadOfNotifyAll.isEmpty());
    }

    @Test
    void lazyInitValidatorDetectsUnsafeLazyInitialization() throws InterruptedException {
        LazyInitValidator validator = new LazyInitValidator();

        validator.recordAccess("singleton", true, true, false, false);
        Thread otherThread = Thread.ofPlatform()
            .start(() -> validator.recordAccess("singleton", true, true, false, false));
        otherThread.join();
        validator.recordAccess("singleton", false, true, false, false);

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertTrue(report.hasIssues());
    }

    @Test
    void futureBlockingDetectorDetectsExecutorStarvationRisk() {
        FutureBlockingDetector detector = new FutureBlockingDetector();
        Object executor = new Object();

        detector.registerExecutor(executor, "boundedPool", 1);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordBlockingWait(executor);

        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.starvationRisks.isEmpty());
    }

    @Test
    void executorDeadlockDetectorDetectsSiblingWaitDeadlock() {
        ExecutorDeadlockDetector detector = new ExecutorDeadlockDetector();
        Object executor = new Object();

        detector.registerExecutor(executor, "singleThreadExecutor", 1);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordWaitingOnSibling(executor);

        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.selfDeadlocks.isEmpty());
    }

    @Test
    void latchMisuseDetectorDetectsMissingAndExtraCountdowns() {
        LatchMisuseDetector detector = new LatchMisuseDetector();
        Object latch = new Object();

        detector.registerLatch(latch, "startupLatch", 2);
        detector.recordAwait(latch);
        detector.recordCountDown(latch);
        detector.recordCountDown(latch);
        detector.recordCountDown(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.extraCountDowns.isEmpty());
    }

    @Test
    void legacyDetectorReportsFormatCleanly() {
        assertNotNull(new NotifyAllValidator().analyze().toString());
        assertNotNull(new LazyInitValidator().analyze().toString());
        assertNotNull(new FutureBlockingDetector().analyze().toString());
        assertNotNull(new ExecutorDeadlockDetector().analyze().toString());
        assertNotNull(new LatchMisuseDetector().analyze().toString());
    }
}
