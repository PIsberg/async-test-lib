package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Phaser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PhaserDetector.
 */
public class PhaserDetectorTest {

    @Test
    void testNormalPhaserUsage() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(3);

        detector.registerPhaser(phaser, "normalPhaser", 3);
        detector.recordArrive(phaser);
        detector.recordArrive(phaser);
        detector.recordArrive(phaser);
        detector.recordPhaseComplete(phaser, 1);

        PhaserDetector.PhaserReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testTimeoutDetection() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);

        detector.registerPhaser(phaser, "timeoutPhaser", 2);
        detector.recordArrive(phaser);
        detector.recordTimeout(phaser);  // Timeout!

        PhaserDetector.PhaserReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect timeout");
    }

    @Test
    void testTerminationDetection() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);

        detector.registerPhaser(phaser, "terminatedPhaser", 2);
        detector.recordTermination(phaser);  // Phaser terminated

        PhaserDetector.PhaserReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect termination");
    }

    @Test
    void testMultiPhasePhaser() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);

        detector.registerPhaser(phaser, "multiPhase", 2);

        // Phase 1
        detector.recordArrive(phaser);
        detector.recordArrive(phaser);
        detector.recordPhaseComplete(phaser, 1);

        // Phase 2
        detector.recordArrive(phaser);
        detector.recordArrive(phaser);
        detector.recordPhaseComplete(phaser, 2);

        PhaserDetector.PhaserReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-phase usage should work correctly");
    }

    @Test
    void testReportToString() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);

        detector.registerPhaser(phaser, "testPhaser", 2);
        detector.recordTimeout(phaser);

        PhaserDetector.PhaserReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("PHASER ISSUES DETECTED"), "Report should have header");
    }

    @Test
    void testNullSafety() {
        PhaserDetector detector = new PhaserDetector();

        detector.recordArrive(null);
        detector.recordTimeout(null);
        detector.recordTermination(null);

        PhaserDetector.PhaserReport report = detector.analyze();
        assertNotNull(report);
    }
}
