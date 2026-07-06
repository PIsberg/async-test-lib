package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BusyWaitDetector.
 */
public class BusyWaitDetectorTest {

    private static final long THRESHOLD = 10_000L;

    @Test
    void noIterationsReturnNoIssues() {
        BusyWaitDetector detector = new BusyWaitDetector();

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No iterations recorded — should report no issues");
        assertTrue(report.busyWaitLoops.isEmpty());
        assertTrue(report.tightLoops.isEmpty());
        assertEquals(0L, report.cpuWasted);
    }

    @Test
    void belowThresholdNoIssues() {
        BusyWaitDetector detector = new BusyWaitDetector();

        // Record just below the spin threshold
        for (int i = 0; i < THRESHOLD - 1; i++) {
            detector.recordLoopIteration();
        }
        detector.recordYield();

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();

        assertFalse(report.hasIssues(),
                "Iterations below SPIN_THRESHOLD_ITERATIONS should not produce a spin report");
        assertTrue(report.busyWaitLoops.isEmpty());
    }

    @Test
    void directSpinReportDetected() {
        BusyWaitDetector detector = new BusyWaitDetector();

        detector.reportSpinLoop("hot-path-loop", THRESHOLD + 5_000);

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();

        assertTrue(report.hasIssues(), "reportSpinLoop() above threshold should register busy-waiting");
        assertFalse(report.busyWaitLoops.isEmpty(), "busyWaitLoops must not be empty");
        assertTrue(report.busyWaitLoops.stream().anyMatch(s -> s.contains("hot-path-loop")),
                "Loop description should appear in busyWaitLoops");
    }

    @Test
    void multipleSpinEventsCollected() {
        BusyWaitDetector detector = new BusyWaitDetector();

        detector.reportSpinLoop("spin-A", 20_000);
        detector.reportSpinLoop("spin-B", 30_000);

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();

        assertTrue(report.hasIssues());
        assertEquals(2, report.busyWaitLoops.size(),
                "Both spin events should be recorded separately");
    }

    @Test
    void disabledDetectorSkipsRecording() {
        BusyWaitDetector detector = new BusyWaitDetector();
        detector.disable();

        detector.reportSpinLoop("should-be-ignored", 50_000);

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();

        assertFalse(report.hasIssues(), "Disabled detector must not record spin events");
    }

    @Test
    void reportToStringContainsSpinInfo() {
        BusyWaitDetector detector = new BusyWaitDetector();
        detector.reportSpinLoop("my-spin-loop", 15_000);

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("BUSY-WAITING"), "toString() should contain BUSY-WAITING header");
        assertTrue(text.contains("my-spin-loop"), "toString() should identify the spin location");
    }

    @Test
    void resetClearsState() {
        BusyWaitDetector detector = new BusyWaitDetector();
        detector.reportSpinLoop("temp", 20_000);

        detector.reset();

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();
        assertFalse(report.hasIssues(), "After reset() all spin events should be cleared");
        assertEquals(0L, report.cpuWasted, "cpuWasted should be 0 after reset");
    }

    @Test
    void yieldResetsLoopCount() {
        BusyWaitDetector detector = new BusyWaitDetector();

        // Record just below the threshold, then yield to reset the count
        for (int i = 0; i < THRESHOLD - 1; i++) {
            detector.recordLoopIteration();
        }
        detector.recordYield();

        // Record another set below the threshold, then yield again
        for (int i = 0; i < THRESHOLD - 1; i++) {
            detector.recordLoopIteration();
        }
        detector.recordYield();

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();

        assertFalse(report.hasIssues(),
                "Two separate below-threshold bursts separated by yield should not trigger detection");
    }

    @Test
    void analyze_delegatesToAnalyzeBusyWaiting() {
        BusyWaitDetector detector = new BusyWaitDetector();
        for (long i = 0; i < THRESHOLD; i++) {
            detector.recordLoopIteration();
        }
        detector.recordYield();

        BusyWaitDetector.BusyWaitReport viaAnalyze = detector.analyze();
        BusyWaitDetector.BusyWaitReport viaAnalyzeBusyWaiting = detector.analyzeBusyWaiting();

        assertEquals(viaAnalyzeBusyWaiting.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeBusyWaiting.toString(), viaAnalyze.toString());
    }
}
