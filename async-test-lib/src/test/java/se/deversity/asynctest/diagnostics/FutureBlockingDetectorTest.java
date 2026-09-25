package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

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

    @Test
    void blockingWaitsThatNeverOverlapDoNotAddUpToAFullPool() {
        Object executor = new Object();
        detector.registerExecutor(executor, "pool", 2);
        for (int round = 0; round < 5; round++) {
            detector.recordTaskSubmitted(executor);   // the task that blocks
            detector.recordTaskStarted(executor);
            detector.recordTaskSubmitted(executor);   // the future it blocks on
            detector.recordTaskStarted(executor);
            detector.recordBlockingWait(executor);
            detector.recordTaskCompleted(executor);   // the future, which ends this thread's wait
            detector.recordTaskCompleted(executor);
        }
        detector.recordTaskSubmitted(executor);       // unrelated work still queued at the end

        assertFalse(detector.analyze().hasIssues(),
            "Five blocking waits one after another never blocked more than one of the two "
                + "workers; a lifetime count of waits is not the number blocked at once");
    }

    @Test
    void anEndedBlockingWaitNoLongerCountsAgainstThePool() {
        Object executor = new Object();
        detector.registerExecutor(executor, "pool", 2);
        for (int round = 0; round < 3; round++) {
            detector.recordTaskSubmitted(executor);
            detector.recordTaskStarted(executor);
            detector.recordBlockingWait(executor);
            detector.recordBlockingWaitEnded(executor);
        }
        detector.recordTaskSubmitted(executor);

        assertFalse(detector.analyze().hasIssues(),
            "each wait ended before the next began, so at most one worker was ever blocked");
    }

    @Test
    void everyWorkerBlockedAtOnceIsReportedEvenAfterTheWaitsTimeOut() throws Exception {
        Object executor = new Object();
        detector.registerExecutor(executor, "pool", 2);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);
        detector.recordTaskSubmitted(executor);       // the future both workers block on
        CyclicBarrier bothBlocked = new CyclicBarrier(2);
        Runnable blocker = () -> {
            detector.recordTaskStarted(executor);
            detector.recordBlockingWait(executor);
            try {
                bothBlocked.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            detector.recordTaskCompleted(executor);   // a bounded get() gave up
        };
        Thread first = new Thread(blocker);
        Thread second = new Thread(blocker);
        first.start();
        second.start();
        first.join(10_000);
        second.join(10_000);

        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertTrue(report.hasIssues(),
            "both workers were blocked at the same moment with the awaited task queued");
        assertTrue(report.toString().contains("2/2 workers blocked"), report.toString());
    }
}
