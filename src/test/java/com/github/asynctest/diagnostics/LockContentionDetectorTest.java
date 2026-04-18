package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LockContentionDetector}.
 */
public class LockContentionDetectorTest {

    @Test
    void testNoContentionNoIssues() {
        LockContentionDetector detector = new LockContentionDetector();
        Object lock = new Object();

        detector.recordAcquireAttempt(lock, "simpleLock");
        detector.recordAcquired(lock, "simpleLock");
        detector.recordReleased(lock, "simpleLock");

        LockContentionDetector.LockContentionReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread acquire with no contention should not be flagged");
    }

    @Test
    void testHighContentionIsDetected() {
        LockContentionDetector detector = new LockContentionDetector();
        Object lock = new Object();

        // 10 attempts, 8 contention events → 80% ratio (above 20% threshold)
        for (int i = 0; i < 10; i++) {
            detector.recordAcquireAttempt(lock, "hotLock");
        }
        for (int i = 0; i < 8; i++) {
            detector.recordContention(lock, "hotLock");
        }
        for (int i = 0; i < 10; i++) {
            detector.recordAcquired(lock, "hotLock");
            detector.recordReleased(lock, "hotLock");
        }

        LockContentionDetector.LockContentionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "80% contention ratio should be flagged");
        assertFalse(report.hotLocks.isEmpty(), "Hot lock entry should be present");
        assertTrue(report.hotLocks.get(0).contains("hotLock"));
    }

    @Test
    void testLowContentionBelowThresholdNotFlagged() {
        LockContentionDetector detector = new LockContentionDetector();
        Object lock = new Object();

        // 100 attempts, 1 contention event → 1% ratio (below 20% threshold and < 5 events)
        for (int i = 0; i < 100; i++) {
            detector.recordAcquireAttempt(lock, "lowContentionLock");
        }
        detector.recordContention(lock, "lowContentionLock");
        for (int i = 0; i < 100; i++) {
            detector.recordAcquired(lock, "lowContentionLock");
            detector.recordReleased(lock, "lowContentionLock");
        }

        LockContentionDetector.LockContentionReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "1% contention with only 1 event should not be flagged");
    }

    @Test
    void testFiveOrMoreContentionEventsAlwaysFlagged() {
        LockContentionDetector detector = new LockContentionDetector();
        Object lock = new Object();

        // 1000 attempts, 5 contention events → 0.5% ratio but meets absolute threshold
        for (int i = 0; i < 1000; i++) {
            detector.recordAcquireAttempt(lock, "absoluteThresholdLock");
        }
        for (int i = 0; i < 5; i++) {
            detector.recordContention(lock, "absoluteThresholdLock");
        }

        LockContentionDetector.LockContentionReport report = detector.analyze();

        assertTrue(report.hasIssues(), "5 contention events should always be flagged regardless of ratio");
    }

    @Test
    void testMultipleLocksTrackedIndependently() {
        LockContentionDetector detector = new LockContentionDetector();
        Object hotLock  = new Object();
        Object coldLock = new Object();

        // Hot lock: high contention
        for (int i = 0; i < 10; i++) {
            detector.recordAcquireAttempt(hotLock, "hotLock");
            detector.recordContention(hotLock, "hotLock");
        }

        // Cold lock: single acquire, no contention
        detector.recordAcquireAttempt(coldLock, "coldLock");
        detector.recordAcquired(coldLock, "coldLock");
        detector.recordReleased(coldLock, "coldLock");

        LockContentionDetector.LockContentionReport report = detector.analyze();

        assertTrue(report.hasIssues(), "Hot lock should trigger issues");
        assertTrue(report.hotLocks.stream().anyMatch(s -> s.contains("hotLock")));
        assertFalse(report.hotLocks.stream().anyMatch(s -> s.contains("coldLock")),
                "Cold lock should not appear in hot-lock list");
    }

    @Test
    void testNullSafety() {
        LockContentionDetector detector = new LockContentionDetector();

        assertDoesNotThrow(() -> {
            detector.recordAcquireAttempt(null, "lock");
            detector.recordContention(null, "lock");
            detector.recordAcquired(null, "lock");
            detector.recordReleased(null, "lock");
        });
    }

    @Test
    void testReportToStringContainsKeywords() {
        LockContentionDetector detector = new LockContentionDetector();
        Object lock = new Object();

        for (int i = 0; i < 10; i++) {
            detector.recordAcquireAttempt(lock, "reportLock");
            detector.recordContention(lock, "reportLock");
        }

        String text = detector.analyze().toString();

        assertNotNull(text);
        assertTrue(text.contains("LOCK CONTENTION"), "Report should contain header");
        assertTrue(text.contains("Fix:"), "Report should suggest a fix");
    }

    @Test
    void testNoAttemptsRecordedSkipsMonitor() {
        LockContentionDetector detector = new LockContentionDetector();

        LockContentionDetector.LockContentionReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Empty detector should have no issues");
    }
}
