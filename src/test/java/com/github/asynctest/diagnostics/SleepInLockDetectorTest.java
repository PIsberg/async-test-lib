package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SleepInLockDetectorTest {

    private SleepInLockDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SleepInLockDetector();
    }

    @Test
    void noIssues_whenNoSleepInLock() {
        detector.startMonitoring();
        detector.recordSleep(10);
        detector.stopMonitoring();

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void disabledDetector_returnsNoIssues() {
        detector.disable();
        detector.startMonitoring();
        detector.recordSleep(100);
        detector.stopMonitoring();

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void clear_removesAllEvents() {
        detector.startMonitoring();
        detector.recordSleep(50);
        detector.clear();

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void report_containsSummary() {
        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        String reportStr = report.toString();
        assertTrue(reportStr.contains("SleepInLockReport"));
    }

    @Test
    void report_showsNoIssues_whenClean() {
        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        String reportStr = report.toString();
        assertTrue(reportStr.contains("No sleep-in-lock patterns detected"));
    }
}
