package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WaitTimeoutDetector.
 */
public class WaitTimeoutDetectorTest {

    @Test
    void testTimedWaitUsage() {
        WaitTimeoutDetector detector = new WaitTimeoutDetector();
        Object lock = new Object();

        detector.recordTimedWait(lock, "testLock", "Thread-1", 5000);  // 5 second timeout

        WaitTimeoutDetector.WaitTimeoutReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Timed wait should not report issues");
    }

    @Test
    void testInfiniteWaitDetection() {
        WaitTimeoutDetector detector = new WaitTimeoutDetector();
        Object lock = new Object();

        detector.recordInfiniteWait(lock, "infiniteLock", "Thread-1");  // No timeout!

        WaitTimeoutDetector.WaitTimeoutReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect infinite wait");
    }

    @Test
    void testNotifyDetection() {
        WaitTimeoutDetector detector = new WaitTimeoutDetector();
        Object lock = new Object();

        detector.recordInfiniteWait(lock, "notifiedLock", "Thread-1");
        detector.recordNotify(lock, "notifiedLock");  // Has notify

        WaitTimeoutDetector.WaitTimeoutReport report = detector.analyze();

        assertNotNull(report);
        // Still reports infinite wait even with notify (it's still risky)
        assertTrue(report.hasIssues(), "Should still report infinite wait");
    }

    @Test
    void testNotifyAllDetection() {
        WaitTimeoutDetector detector = new WaitTimeoutDetector();
        Object lock = new Object();

        detector.recordInfiniteWait(lock, "notifiedAllLock", "Thread-1");
        detector.recordNotifyAll(lock, "notifiedAllLock");  // Has notifyAll

        WaitTimeoutDetector.WaitTimeoutReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should still report infinite wait");
    }

    @Test
    void testMultiThreadWait() throws Exception {
        WaitTimeoutDetector detector = new WaitTimeoutDetector();
        Object lock = new Object();

        Thread t1 = new Thread(() -> {
            detector.recordTimedWait(lock, "multiLock", "Thread-1", 1000);
        });

        Thread t2 = new Thread(() -> {
            detector.recordTimedWait(lock, "multiLock", "Thread-2", 1000);
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        WaitTimeoutDetector.WaitTimeoutReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-thread timed wait should work correctly");
    }

    @Test
    void testReportToString() {
        WaitTimeoutDetector detector = new WaitTimeoutDetector();
        Object lock = new Object();

        detector.recordInfiniteWait(lock, "testLock", "Thread-1");

        WaitTimeoutDetector.WaitTimeoutReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("WAIT TIMEOUT ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Infinite wait"), "Report should mention infinite wait");
    }

    @Test
    void testNullSafety() {
        WaitTimeoutDetector detector = new WaitTimeoutDetector();

        // Should not throw on null inputs
        detector.recordInfiniteWait(null, "null", "Thread-1");
        detector.recordTimedWait(null, "null", "Thread-1", 1000);
        detector.recordNotify(null, "null");
        detector.recordNotifyAll(null, "null");

        WaitTimeoutDetector.WaitTimeoutReport report = detector.analyze();
        assertNotNull(report);
    }
}
