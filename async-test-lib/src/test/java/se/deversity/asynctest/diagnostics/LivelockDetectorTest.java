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

    /**
     * The limit the catalog used to describe backwards: a busy spin is not reported.
     *
     * <p>{@code madeProgress} returns true for any RUNNABLE thread, on purpose, because a busy
     * worker's measured CPU time can look flat when several snapshots land inside one clock tick
     * and reporting those produced findings against healthy JVMs. The consequence is that a
     * spin-retry loop burning attempts without completing work - which is what the word livelock
     * usually means - is not something this detector reports. {@code examples/07-livelock} was
     * retired over exactly this in #362, and the catalog claimed the opposite until #373.
     *
     * <p>Pinned rather than written down, so the prose cannot drift back.
     */
    @Test
    void aBusyRunnableThreadIsNotReported() throws Exception {
        LivelockDetector detector = new LivelockDetector();

        Thread spinner = new Thread(() -> {
            long sink = 0;
            for (int i = 0; i < 40; i++) {
                for (int j = 0; j < 50_000; j++) {
                    sink += j;                      // genuinely on the CPU between snapshots
                }
                if (sink == Long.MIN_VALUE) {
                    throw new IllegalStateException("unreachable");
                }
                detector.captureSnapshot();
            }
        }, "busy-spinner");
        spinner.start();
        spinner.join(30_000);

        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();
        assertFalse(report.hasIssues(),
                "forty snapshots of a thread that was RUNNABLE throughout. This detector reports "
                        + "starvation and rapid state cycling, not a busy spin, and a finding here "
                        + "would be a false positive on a thread that was doing work the whole "
                        + "time. Report: " + report);
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
