package se.deversity.asynctest.diagnostics;

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
    void testAwaitOnBrokenBarrierIsFlagged() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "reuseAfterBrokenBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordBroken(barrier);
        detector.recordAwait(barrier);  // Reused without reset - should be flagged

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect reuse of a broken barrier");
        assertTrue(report.getReuseAfterBrokenBarriers().contains(barrier),
            "Barrier should be tracked as reused after broken");
    }

    @Test
    void testAwaitAfterResetIsNotFlagged() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "resetBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordBroken(barrier);
        detector.recordReset(barrier);  // Repaired before reuse
        detector.recordAwait(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.getReuseAfterBrokenBarriers().contains(barrier),
            "Barrier reset before reuse should not be flagged");
        assertFalse(report.hasIssues(), "Reset barrier reused correctly should not report issues");
    }

    @Test
    void testAwaitOnNeverBrokenBarrierIsNotFlagged() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "healthyBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordAwait(barrier);
        detector.recordAwait(barrier);
        detector.recordBarrierComplete(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.getReuseAfterBrokenBarriers().contains(barrier),
            "Barrier that never broke should not be flagged");
        assertFalse(report.hasIssues(), "Healthy barrier usage should not report issues");
    }

    @Test
    void testReuseAfterBrokenDescribedInReport() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "describedBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordBroken(barrier);
        detector.recordAwait(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        String reportStr = report.toString();
        assertTrue(reportStr.contains("Reuse After Broken Barriers"), "Report should have a reuse section");
        assertTrue(reportStr.contains("BrokenBarrierException"), "Report should explain the hazard");
        assertTrue(reportStr.contains("reset()"), "Report should mention the fix");
    }

    @Test
    void nullBarrierIsIgnoredOnEveryRecordPath() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        assertDoesNotThrow(() -> {
            detector.registerBarrier(null, "n", 2);
            detector.recordArrival(null);
            detector.recordTimeout(null);
            detector.recordBroken(null);
            detector.recordReset(null);
            detector.recordAwait(null);
            detector.recordBarrierComplete(null);
            detector.analyze();
        });
    }
}
