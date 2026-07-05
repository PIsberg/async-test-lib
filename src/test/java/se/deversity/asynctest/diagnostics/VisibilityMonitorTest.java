package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VisibilityMonitor.
 */
public class VisibilityMonitorTest {

    @Test
    void noAccessesReturnsNoIssues() {
        VisibilityMonitor monitor = new VisibilityMonitor();

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No accesses recorded — should have no issues");
        assertTrue(report.suspectedFields.isEmpty(), "suspectedFields should be empty");
    }

    @Test
    void singleValueFieldNoIssues() {
        VisibilityMonitor monitor = new VisibilityMonitor();

        // Same field, same value, across two invocations
        monitor.markInvocationStart();
        monitor.recordFieldAccess("MyClass.counter", 42);
        monitor.markInvocationStart();
        monitor.recordFieldAccess("MyClass.counter", 42);

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertNotNull(report);
        assertFalse(report.hasIssues(),
                "Field with constant value across invocations should not report visibility issues");
    }

    @Test
    void fieldWithVariationsDetected() {
        VisibilityMonitor monitor = new VisibilityMonitor();

        // Invocation 1: field = 10
        monitor.markInvocationStart();
        monitor.recordFieldAccess("MyClass.sharedField", 10);

        // Invocation 2: field = 99 — simulates stale read by another thread
        monitor.markInvocationStart();
        monitor.recordFieldAccess("MyClass.sharedField", 99);

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Different values across invocations should flag visibility issue");
        assertTrue(report.suspectedFields.contains("MyClass.sharedField"),
                "suspectedFields should contain the varying field");
    }

    @Test
    void disabledDetectorSkipsRecording() {
        VisibilityMonitor monitor = new VisibilityMonitor();
        monitor.disable();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("SomeClass.flag", "value-A");
        monitor.markInvocationStart();
        monitor.recordFieldAccess("SomeClass.flag", "value-B");

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertFalse(report.hasIssues(), "Disabled monitor should not record any accesses");
    }

    @Test
    void resetClearsState() {
        VisibilityMonitor monitor = new VisibilityMonitor();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("A.x", 1);
        monitor.markInvocationStart();
        monitor.recordFieldAccess("A.x", 2);

        monitor.reset();

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertFalse(report.hasIssues(), "After reset() all state should be cleared");
        assertTrue(report.suspectedFields.isEmpty(), "suspectedFields should be empty after reset");
    }

    @Test
    void reportToStringContainsIssues() {
        VisibilityMonitor monitor = new VisibilityMonitor();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("Service.cache", "v1");
        monitor.markInvocationStart();
        monitor.recordFieldAccess("Service.cache", "v2");

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        String text = report.toString();
        assertNotNull(text);
        assertTrue(text.contains("POTENTIAL VISIBILITY ISSUES"),
                "toString() for a report with issues should contain POTENTIAL VISIBILITY ISSUES");
        assertTrue(text.contains("Service.cache"),
                "toString() should name the suspected field");
    }

    @Test
    void nullSafetyOnDisable() {
        VisibilityMonitor monitor = new VisibilityMonitor();
        // disable/enable round-trip should be safe and re-enable recording
        monitor.disable();
        monitor.enable();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("A.field", 1);
        monitor.markInvocationStart();
        monitor.recordFieldAccess("A.field", 2);

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();
        assertTrue(report.hasIssues(), "Monitor should record after re-enable");
    }

    @Test
    void analyze_delegatesToAnalyzeVisibility() {
        VisibilityMonitor monitor = new VisibilityMonitor();
        monitor.recordFieldAccess("A.field", 1);
        monitor.markInvocationStart();
        monitor.recordFieldAccess("A.field", 2);

        VisibilityMonitor.VisibilityReport viaAnalyze = monitor.analyze();
        VisibilityMonitor.VisibilityReport viaAnalyzeVisibility = monitor.analyzeVisibility();

        assertEquals(viaAnalyzeVisibility.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeVisibility.toString(), viaAnalyze.toString());
    }
}
