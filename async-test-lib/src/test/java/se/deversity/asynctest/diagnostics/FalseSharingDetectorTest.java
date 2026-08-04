package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FalseSharingDetectorTest {

    @Test
    void noRecordingsReturnNoIssues() {
        FalseSharingDetector detector = new FalseSharingDetector();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
    }

    @Test
    void singleThreadSingleFieldNoIssues() {
        FalseSharingDetector detector = new FalseSharingDetector();
        Object obj = new Object();
        detector.recordFieldAccess(obj, "counter", int.class);
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
    }

    @Test
    void multipleThreadsAccessingSameFieldRecorded() throws InterruptedException {
        FalseSharingDetector detector = new FalseSharingDetector();
        Object obj = new Object();
        Thread t1 = new Thread(() -> detector.recordFieldAccess(obj, "value", long.class));
        Thread t2 = new Thread(() -> detector.recordFieldAccess(obj, "value", long.class));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        // Should complete without throwing; report state is implementation-dependent
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertNotNull(report);
    }

    @Test
    void reportHasIssuesFalseWhenEmpty() {
        FalseSharingDetector detector = new FalseSharingDetector();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
        assertTrue(report.falseSharedPairs.isEmpty());
        assertTrue(report.highContentionFields.isEmpty());
    }

    @Test
    void reportToStringNoIssues() {
        FalseSharingDetector detector = new FalseSharingDetector();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        String text = report.toString();
        assertNotNull(text);
        assertTrue(text.contains("No false sharing detected.") || !report.hasIssues());
    }

    @Test
    void resetClearsState() {
        FalseSharingDetector detector = new FalseSharingDetector();
        Object obj = new Object();
        detector.recordFieldAccess(obj, "field", int.class);
        detector.reset();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
        assertTrue(report.falseSharedPairs.isEmpty());
    }

    @Test
    void disabledDetectorSkipsRecording() {
        FalseSharingDetector detector = new FalseSharingDetector();
        detector.disable();
        Object obj = new Object();
        detector.recordFieldAccess(obj, "x", double.class);
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
        detector.enable();
    }

    @Test
    void nullObjectHandledGracefully() {
        FalseSharingDetector detector = new FalseSharingDetector();
        assertDoesNotThrow(() -> detector.recordFieldAccess(null, "field", int.class));
    }

    @Test
    void analyze_delegatesToAnalyzeFalseSharing() {
        FalseSharingDetector detector = new FalseSharingDetector();
        Object obj = new Object();
        detector.recordFieldAccess(obj, "field", int.class);

        FalseSharingDetector.FalseSharingReport viaAnalyze = detector.analyze();
        FalseSharingDetector.FalseSharingReport viaAnalyzeFalseSharing = detector.analyzeFalseSharing();

        assertEquals(viaAnalyzeFalseSharing.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeFalseSharing.toString(), viaAnalyze.toString());
    }

    /** Two int fields of the same class, each touched by two distinct threads so the
     * thread sets are unequal and both fields pass the multi-thread guard regardless of
     * map iteration order: under the detector's declaration-order offset model this is
     * an adjacent-fields, unequal-thread-sets pair, i.e. exactly what it reports as
     * false sharing. */
    private static FalseSharingDetector detectorWithReportablePattern() throws InterruptedException {
        FalseSharingDetector detector = new FalseSharingDetector();
        TwoCounters obj = new TwoCounters();
        Thread t1 = new Thread(() -> detector.recordFieldAccess(obj, "a", int.class));
        Thread t2 = new Thread(() -> detector.recordFieldAccess(obj, "a", int.class));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        Thread t3 = new Thread(() -> detector.recordFieldAccess(obj, "b", int.class));
        Thread t4 = new Thread(() -> detector.recordFieldAccess(obj, "b", int.class));
        t3.start();
        t4.start();
        t3.join();
        t4.join();
        return detector;
    }

    @Test
    void findingsAreGatedBehindTheExperimentalFlag() throws InterruptedException {
        FalseSharingDetector detector = detectorWithReportablePattern();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues(),
                "Without -Dasync-test.experimental.false-sharing=true the detector must "
                        + "report nothing: its offsets are declaration-order arithmetic that "
                        + "ignores JVM field reordering and compressed oops, its keying is "
                        + "per-class rather than per-object, and its pair predicate requires "
                        + "unequal thread sets, which excludes the textbook case. Findings "
                        + "uncorrelated with the named phenomenon must be opt-in.");
    }

    @Test
    void experimentalFlagRestoresTheOldBehavior() throws InterruptedException {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            FalseSharingDetector detector = detectorWithReportablePattern();
            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertTrue(report.hasIssues(),
                    "With the experimental property set, the pre-gate analysis must run "
                            + "unchanged so existing users can opt back in");
            assertFalse(report.falseSharedPairs.isEmpty());
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    static class TwoCounters {
        int a;
        int b;
    }
}
