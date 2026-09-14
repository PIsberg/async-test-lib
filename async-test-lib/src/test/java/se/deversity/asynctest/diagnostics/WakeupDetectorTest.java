package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WakeupDetectorTest {

    @Test
    void noRecordingsReturnNoIssues() {
        WakeupDetector detector = new WakeupDetector();
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        assertFalse(report.hasIssues());
    }

    @Test
    void notifiedWakeupNoIssues() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        detector.recordWaitEnter(monitor);
        detector.recordNotify(monitor, false);
        detector.recordWaitExit(monitor, true);
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        assertFalse(report.hasIssues());
    }

    @Test
    void spuriousWakeupDetected() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        assertFalse(report.monitorsWithSpuriousWakeups.isEmpty());
    }

    @Test
    void notifyWithoutWaiterIsContextNotAFinding() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        // notify without any prior waitEnter: the flag-then-notifyAll handshake does this (#590)
        detector.recordNotify(monitor, false);
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        assertFalse(report.hasIssues());
        assertEquals(1, report.monitorsWithLostNotifications.size());
    }

    @Test
    void reportHasIssuesFalseWhenEmpty() {
        WakeupDetector detector = new WakeupDetector();
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        assertFalse(report.hasIssues());
        assertTrue(report.monitorsWithSpuriousWakeups.isEmpty());
        assertTrue(report.monitorsWithLostNotifications.isEmpty());
    }

    @Test
    void reportToStringNoIssues() {
        WakeupDetector detector = new WakeupDetector();
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void resetClearsState() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false); // spurious
        assertTrue(detector.analyzeWakeups().hasIssues());
        detector.reset();
        assertFalse(detector.analyzeWakeups().hasIssues());
    }

    @Test
    void disabledSkipsRecording() {
        WakeupDetector detector = new WakeupDetector();
        detector.disable();
        Object monitor = new Object();
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        assertFalse(report.hasIssues());
        detector.enable();
    }

    @Test
    void analyze_delegatesToAnalyzeWakeups() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);

        WakeupDetector.WakeupReport viaAnalyze = detector.analyze();
        WakeupDetector.WakeupReport viaAnalyzeWakeups = detector.analyzeWakeups();

        assertEquals(viaAnalyzeWakeups.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeWakeups.toString(), viaAnalyze.toString());
    }

    @Test
    void nullMonitorIsIgnoredAndDoesNotBreakAnalysis() {
        WakeupDetector detector = new WakeupDetector();
        assertDoesNotThrow(() -> {
            detector.recordWaitEnter(null);
            detector.recordNotify(null, false);
            detector.recordWaitExit(null, true);
            detector.analyzeWakeups();
        });
    }
}
