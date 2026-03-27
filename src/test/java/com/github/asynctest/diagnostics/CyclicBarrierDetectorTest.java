package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CyclicBarrierDetector.
 */
public class CyclicBarrierDetectorTest {

    @Test
    void testNormalBarrierUsage() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(3);

        detector.registerBarrier(barrier, "normalBarrier", 3);
        detector.recordArrival(barrier);
        detector.recordArrival(barrier);
        detector.recordArrival(barrier);
        detector.recordBarrierComplete(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testTimeoutDetection() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(3);

        detector.registerBarrier(barrier, "timeoutBarrier", 3);
        detector.recordArrival(barrier);
        detector.recordArrival(barrier);
        // Missing third arrival - timeout!
        detector.recordTimeout(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect timeout");
    }

    @Test
    void testBrokenBarrierDetection() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "brokenBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordBroken(barrier);  // Barrier broken

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect broken barrier");
    }

    @Test
    void testMultiCycleBarrier() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "multiCycle", 2);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-cycle usage should work correctly");
    }

    @Test
    void testReportToString() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "testBarrier", 2);
        detector.recordTimeout(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("CYCLICBARRIER ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Timed Out"), "Report should mention timeout");
    }

    @Test
    void testNullSafety() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();

        // Should not throw on null inputs
        detector.recordArrival(null);
        detector.recordTimeout(null);
        detector.recordBroken(null);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();
        assertNotNull(report);
    }
}
