package com.github.asynctest.diagnostics;

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
}
