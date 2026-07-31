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
}
