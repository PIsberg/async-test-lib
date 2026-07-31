package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LivelockDetector.
 */
public class LivelockDetectorTest {

    @Test
    void emptySnapshotsReturnNoIssues() {
        LivelockDetector detector = new LivelockDetector();

        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No snapshots captured — should report no issues");
    }

    @Test
    void captureSnapshotRunsWithoutError() {
        LivelockDetector detector = new LivelockDetector();

        // captureSnapshot() must not throw under normal JVM conditions
        assertDoesNotThrow(detector::captureSnapshot);
    }

    @Test
    void reportHasIssuesFalseWhenEmpty() {
        LivelockDetector.LivelockReport report = new LivelockDetector.LivelockReport();

        assertFalse(report.hasIssues(),
                "Empty report (no starved, livelock, or no-progress threads) must not have issues");
    }

    @Test
    void reportToStringNoIssuesMessage() {
        LivelockDetector.LivelockReport report = new LivelockDetector.LivelockReport();

        String text = report.toString();
        assertNotNull(text);
        assertEquals("No livelock or starvation issues detected.", text,
                "Empty report toString() should return the clean no-issue message");
    }

    @Test
    void reportToStringWithIssuesContainsDetails() {
        LivelockDetector.LivelockReport report = new LivelockDetector.LivelockReport();
        report.starvedThreads.add("worker-1");
        report.livelockCandidates.add("worker-2");
        report.noProgressThreads.add("worker-3");

        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("LIVELOCK"), "Should mention LIVELOCK / STARVATION in header");
        assertTrue(text.contains("worker-1"), "Should list starved thread");
        assertTrue(text.contains("worker-2"), "Should list livelock candidate");
        assertTrue(text.contains("worker-3"), "Should list no-progress thread");
        assertTrue(report.hasIssues(), "Populated report must report hasIssues() == true");
    }

    @Test
    void resetClearsState() {
        LivelockDetector detector = new LivelockDetector();

        // Capture some snapshots
        detector.captureSnapshot();
        detector.captureSnapshot();

        detector.reset();

        // After reset the history should be empty — no issues can be detected
        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();
        assertFalse(report.hasIssues(), "After reset() no issues should be detectable");
    }

    @Test
    void analyzeAfterCaptureReturnsReport() {
        LivelockDetector detector = new LivelockDetector();

        detector.captureSnapshot();
        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();

        assertNotNull(report, "analyzeLivelocks() must return a non-null report");
        // A single snapshot cannot produce starvation/livelock findings (requires >=2 snapshots)
        assertFalse(report.hasIssues(),
                "Single snapshot is insufficient for livelock/starvation detection");
    }

    @Test
    void analyze_delegatesToAnalyzeLivelocks() {
        LivelockDetector detector = new LivelockDetector();
        detector.captureSnapshot();

        LivelockDetector.LivelockReport viaAnalyze = detector.analyze();
        LivelockDetector.LivelockReport viaAnalyzeLivelocks = detector.analyzeLivelocks();

        assertEquals(viaAnalyzeLivelocks.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeLivelocks.toString(), viaAnalyze.toString());
    }
}
