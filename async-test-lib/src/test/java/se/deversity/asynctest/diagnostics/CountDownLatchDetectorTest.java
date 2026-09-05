package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CountDownLatchDetector.
 */
public class CountDownLatchDetectorTest {

    @Test
    void testNormalLatchUsage() throws Exception {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(2);

        detector.registerLatch(latch, "normalLatch", 2);
        detector.recordCountDown(latch);
        detector.recordCountDown(latch);
        detector.recordAwaitSuccess(latch);

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testExtraCountDownDetection() {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(1);

        detector.registerLatch(latch, "extra-countdown", 1);
        detector.recordCountDown(latch);  // First countdown (valid)
        detector.recordCountDown(latch);  // Extra countdown (bug!)

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect extra countDown");
    }

    @Test
    void testTimeoutDetection() {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(1);

        detector.registerLatch(latch, "timeoutLatch", 1);
        detector.recordTimeout(latch);  // Simulate timeout

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect timeout");
    }

    @Test
    void testMissingCountDown() {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(2);

        detector.registerLatch(latch, "missing-countdown", 2);
        detector.recordCountDown(latch);  // Only one countdown (missing one)
        // Missing second countdown

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();

        assertNotNull(report);
        // Note: Missing countdown alone doesn't trigger issue without timeout
        // The issue is detected when await times out
    }

    @Test
    void testMultiThreadLatchUsage() throws Exception {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(3);

        detector.registerLatch(latch, "multi-thread", 3);

        Thread t1 = new Thread(() -> {
            detector.recordCountDown(latch);
            latch.countDown();
        });

        Thread t2 = new Thread(() -> {
            detector.recordCountDown(latch);
            latch.countDown();
        });

        Thread t3 = new Thread(() -> {
            detector.recordCountDown(latch);
            latch.countDown();
        });

        t1.start();
        t2.start();
        t3.start();

        t1.join();
        t2.join();
        t3.join();

        detector.recordAwaitSuccess(latch);

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-thread usage should work correctly");
    }

    @Test
    void testReportToString() {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(1);

        detector.registerLatch(latch, "testLatch", 1);
        detector.recordTimeout(latch);

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("COUNTDOWNLATCH ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Timed Out"), "Report should mention timeout");
    }

    @Test
    void aLatchThatTimedOutAndLaterSucceededIsNotAFinding() {
        // A CountDownLatch only ever counts down, and once it is at zero every await returns
        // immediately: a latch cannot succeed and then start timing out again. So a recorded
        // success proves the latch did reach zero, which makes every timeout recorded against it
        // a wait that started too early - a slow start, not a countDown() that never came.
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch slowStarter = new CountDownLatch(1);

        detector.registerLatch(slowStarter, "slow-starter", 1);
        detector.recordTimeout(slowStarter);
        detector.recordCountDown(slowStarter);
        detector.recordAwaitSuccess(slowStarter);

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();

        assertFalse(report.hasIssues(),
                "the latch reached zero and a later await proved it; reporting the earlier "
                        + "timeout accuses code that worked. Report: " + report);
        assertFalse(report.toString().contains("slow-starter"),
                "and the latch must not be named in the report at all: " + report);
    }

    @Test
    void aLatchThatOnlyEverTimedOutIsStillAFinding() {
        // The other direction of the rule above. Without this, suppressing on success could be
        // implemented as suppressing everything and the test above would still pass.
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch neverFell = new CountDownLatch(1);
        CountDownLatch succeeded = new CountDownLatch(1);

        detector.registerLatch(neverFell, "never-fell", 1);
        detector.registerLatch(succeeded, "succeeded", 1);
        detector.recordTimeout(neverFell);
        detector.recordTimeout(succeeded);
        detector.recordAwaitSuccess(succeeded);

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();

        assertTrue(report.hasIssues(), "the latch that never fell is still a finding: " + report);
        assertTrue(report.toString().contains("never-fell"),
                "and must be named: " + report);
        assertFalse(report.toString().contains("succeeded"),
                "while the one that later succeeded is not; a success on one latch may not "
                        + "silence another: " + report);
    }

    @Test
    void theSuppressionWorksForALatchNothingRegistered() {
        // The agent-fed path: AgentConcurrencyUtilHooks records timeouts and successes but never
        // calls registerLatch, so a rule that lived in the registry entry would do nothing there,
        // which is where these records mostly come from.
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch unregistered = new CountDownLatch(1);

        detector.recordTimeout(unregistered);
        detector.recordAwaitSuccess(unregistered);

        assertFalse(detector.analyze().hasIssues(),
                "the same rule has to hold for a latch that only the hooks ever saw");
    }

    @Test
    void nullLatchIsIgnoredOnEveryRecordPath() {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        assertDoesNotThrow(() -> {
            detector.registerLatch(null, "n", 1);
            detector.recordCountDown(null);
            detector.recordTimeout(null);
            detector.recordAwaitSuccess(null);
            detector.analyze();
        });
    }
}
