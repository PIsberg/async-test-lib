package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryOrderingMonitorTest {

    @Test
    void noRecordingsReturnNoIssues() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertFalse(report.hasIssues());
    }

    @Test
    void singleThreadReadWriteNoIssues() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        monitor.recordWrite("location1", 42);
        monitor.recordRead("location1", 42);
        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertFalse(report.hasIssues());
    }

    @Test
    void multipleLocationsTracked() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        monitor.recordWrite("fieldA", "hello");
        monitor.recordWrite("fieldB", 100);
        monitor.recordRead("fieldA", "hello");
        monitor.recordRead("fieldB", 100);
        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertNotNull(report);
        assertNotNull(report.staleCoreads);
        assertNotNull(report.suspiciousReorderings);
    }

    @Test
    void reportHasIssuesFalseByDefault() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertFalse(report.hasIssues());
        assertTrue(report.staleCoreads.isEmpty());
        assertTrue(report.suspiciousReorderings.isEmpty());
    }

    @Test
    void reportToStringNoIssues() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void resetClearsState() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        monitor.recordWrite("x", 1);
        monitor.recordRead("x", 99);
        monitor.reset();
        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertFalse(report.hasIssues());
        assertTrue(report.staleCoreads.isEmpty());
        assertTrue(report.suspiciousReorderings.isEmpty());
    }

    @Test
    void disabledSkipsRecording() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        monitor.disable();
        monitor.recordWrite("loc", "value");
        monitor.recordRead("loc", "stale");
        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertFalse(report.hasIssues());
        monitor.enable();
    }

    /** Runs {@code body} on a new thread and waits for it. */
    private static void onThread(Runnable body) throws InterruptedException {
        Thread t = new Thread(body);
        t.start();
        t.join();
    }

    @Test
    void aReadOfAValueAWriteRecordedLaterProducedIsTheLogLaggingNotAStaleRead() throws InterruptedException {
        // T2 writes 2 and T3 reads it before T2's record lands: the log shows T1's write of 1,
        // then T3's read of 2, then T2's write of 2. The read saw the newest write, correctly.
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        onThread(() -> monitor.recordWrite("x", 1));
        onThread(() -> monitor.recordRead("x", 2));
        onThread(() -> monitor.recordWrite("x", 2));

        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertFalse(report.hasIssues(),
            "a later record explains the value the read returned: " + report);
    }

    @Test
    void aReadThatDisagreesWithThePrecedingRecordedWriteSaysOnlyWhatTheRecordsShow() throws InterruptedException {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        onThread(() -> monitor.recordWrite("flag", true));
        onThread(() -> monitor.recordRead("flag", false));

        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertTrue(report.hasIssues(), report.toString());
        String finding = report.staleCoreads.iterator().next();
        assertTrue(finding.contains("Record order is not memory order"),
            "call order cannot prove the read was stale, and the finding must not claim it: " + finding);
        assertTrue(finding.contains("happens-before"), finding);
        assertTrue(report.toString().contains("HIGH"), report.toString());
    }

    @Test
    void analyze_delegatesToAnalyzeOrdering() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        monitor.recordWrite("fieldA", "hello");
        monitor.recordRead("fieldA", "stale");

        MemoryOrderingMonitor.MemoryOrderingReport viaAnalyze = monitor.analyze();
        MemoryOrderingMonitor.MemoryOrderingReport viaAnalyzeOrdering = monitor.analyzeOrdering();

        assertEquals(viaAnalyzeOrdering.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeOrdering.toString(), viaAnalyze.toString());
    }
}
