package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DoubleCheckedLockingDetector.
 */
public class DoubleCheckedLockingDetectorTest {

    @Test
    void testVolatileDCL() {
        DoubleCheckedLockingDetector detector = new DoubleCheckedLockingDetector();

        // Proper DCL with volatile - should not report issues
        detector.registerDCL("volatileInstance", true, true, true, true);

        DoubleCheckedLockingDetector.DoubleCheckedLockingReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Volatile DCL should not report issues");
    }

    @Test
    void testBrokenDCLDetection() {
        DoubleCheckedLockingDetector detector = new DoubleCheckedLockingDetector();

        // Broken DCL without volatile - should report issues
        detector.registerDCL("brokenInstance", false, true, true, true);

        DoubleCheckedLockingDetector.DoubleCheckedLockingReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect broken DCL");
    }

    @Test
    void testNonDCLPattern() {
        DoubleCheckedLockingDetector detector = new DoubleCheckedLockingDetector();

        // Not a DCL pattern (no first check) - should not report issues
        detector.registerDCL("normalField", false, false, false, false);

        DoubleCheckedLockingDetector.DoubleCheckedLockingReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Non-DCL pattern should not report issues");
    }

    @Test
    void testReportToString() {
        DoubleCheckedLockingDetector detector = new DoubleCheckedLockingDetector();

        detector.registerDCL("testInstance", false, true, true, true);

        DoubleCheckedLockingDetector.DoubleCheckedLockingReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("DOUBLE-CHECKED LOCKING ISSUES DETECTED"), 
                   "Report should have header");
        assertTrue(reportStr.contains("volatile"), "Report should mention volatile");
    }

    @Test
    void testAccessRecording() {
        DoubleCheckedLockingDetector detector = new DoubleCheckedLockingDetector();

        detector.registerDCL("instance", false, true, true, true);
        detector.recordAccess("instance", true, false);  // Read
        detector.recordAccess("instance", false, true);  // Write

        DoubleCheckedLockingDetector.DoubleCheckedLockingReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should still detect broken DCL");
    }

    @Test
    void testNullSafety() {
        DoubleCheckedLockingDetector detector = new DoubleCheckedLockingDetector();

        // Should not throw on null inputs
        detector.recordAccess(null, true, true);

        DoubleCheckedLockingDetector.DoubleCheckedLockingReport report = detector.analyze();
        assertNotNull(report);
    }
}
