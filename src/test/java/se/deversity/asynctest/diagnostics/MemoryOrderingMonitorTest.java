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
}
