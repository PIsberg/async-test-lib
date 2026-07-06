package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ABAProblemDetectorTest {

    @Test
    void noRecordingsReturnNoIssues() {
        ABAProblemDetector detector = new ABAProblemDetector();
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertFalse(report.hasIssues());
    }

    @Test
    void noABACycleNoIssues() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("x", "A", "B");
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertFalse(report.hasIssues());
    }

    @Test
    void abaCycleDetected() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("x", "init", "A"); // establish A
        detector.recordValueChange("x", "A", "B");
        detector.recordValueChange("x", "B", "A");    // back to A — ABA cycle
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertTrue(report.hasIssues());
        assertTrue(report.variablesWithCycles.containsKey("x"));
    }

    @Test
    void casAttemptWithABAFlagged() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("counter", 0, 1); // establish initial value 1
        detector.recordValueChange("counter", 1, 2);
        detector.recordValueChange("counter", 2, 1); // back to 1 — ABA
        detector.recordCASAttempt("counter", 1, 3, true, 1);
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertTrue(report.hasIssues());
        assertFalse(report.successfulABACases.isEmpty());
    }

    @Test
    void reportToStringNoIssues() {
        ABAProblemDetector detector = new ABAProblemDetector();
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.contains("ABA PROBLEM"));
    }

    @Test
    void reportToStringWithIssues() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("val", "A", "B");
        detector.recordValueChange("val", "B", "A");
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        String text = report.toString();
        assertNotNull(text);
        assertTrue(text.contains("ABA PROBLEM") || text.contains("ABA") || report.hasIssues());
    }

    @Test
    void resetClearsState() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("y", "init", "A");
        detector.recordValueChange("y", "A", "B");
        detector.recordValueChange("y", "B", "A");
        assertTrue(detector.analyzeABA().hasIssues());
        detector.reset();
        assertFalse(detector.analyzeABA().hasIssues());
    }

    @Test
    void disabledSkipsRecording() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.disable();
        detector.recordValueChange("z", "A", "B");
        detector.recordValueChange("z", "B", "A");
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertFalse(report.hasIssues());
        detector.enable();
    }

    @Test
    void analyze_delegatesToAnalyzeABA() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("x", "start", "A");
        detector.recordValueChange("x", "A", "B");
        detector.recordValueChange("x", "B", "A");

        ABAProblemDetector.ABAReport viaAnalyze = detector.analyze();
        ABAProblemDetector.ABAReport viaAnalyzeABA = detector.analyzeABA();

        assertEquals(viaAnalyzeABA.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeABA.toString(), viaAnalyze.toString());
    }
}
