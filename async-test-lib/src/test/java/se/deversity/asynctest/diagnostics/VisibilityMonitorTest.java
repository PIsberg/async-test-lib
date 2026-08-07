package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VisibilityMonitor.
 *
 * <p>The monitor reports a field only when two threads observed different values within
 * the same invocation round — the stale-read signature. Cross-round variation is ordered
 * by the harness and must never be reported. Helpers below record from a short-lived
 * second thread to build the two-observer shape.
 */
public class VisibilityMonitorTest {

    /** Records one access from a brand-new thread and waits for it to finish. */
    private static void recordOnNewThread(VisibilityMonitor monitor, String field, Object value)
            throws InterruptedException {
        Thread recorder = new Thread(() -> monitor.recordFieldAccess(field, value));
        recorder.start();
        recorder.join();
    }

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

    // ---- Null values: the canonical stale read a visibility monitor must accept ----

    @Test
    void nullValueRecordingDoesNotThrow() {
        VisibilityMonitor monitor = new VisibilityMonitor();

        monitor.markInvocationStart();
        assertDoesNotThrow(() -> monitor.recordFieldAccess("Holder.ref", null),
                "null is the canonical stale read (a field observed before another thread's "
                        + "write became visible) — recording it must not throw");
    }

    @Test
    void nullValueParticipatesInDivergenceAnalysis() throws InterruptedException {
        VisibilityMonitor monitor = new VisibilityMonitor();

        // Same round: this thread sees the initialized value, a second thread reads back
        // a stale null.
        monitor.markInvocationStart();
        monitor.recordFieldAccess("Holder.ref", "initialized");
        recordOnNewThread(monitor, "Holder.ref", null);

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertTrue(report.hasIssues(),
                "null vs non-null seen by two threads in one round is exactly the stale-read "
                        + "signature this monitor exists to flag");
        assertTrue(report.suspectedFields.contains("Holder.ref"),
                "suspectedFields should contain the field that read back null");
    }

    // ---- Within-round divergence is the signal; cross-round variation is not ----

    @Test
    void staleReadWithinOneInvocationDetected() throws InterruptedException {
        VisibilityMonitor monitor = new VisibilityMonitor();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("MyClass.sharedField", 10);
        recordOnNewThread(monitor, "MyClass.sharedField", 99); // second thread, same round

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertTrue(report.hasIssues(),
                "two threads observing different values within one round must be flagged");
        assertTrue(report.suspectedFields.contains("MyClass.sharedField"),
                "suspectedFields should contain the divergent field");
    }

    @Test
    void singleThreadVariationAcrossInvocationsIsNotFlagged() {
        VisibilityMonitor monitor = new VisibilityMonitor();

        // A counter legitimately changing between rounds, observed by one thread: rounds
        // are ordered by the harness, so this cannot be a visibility issue. The previous
        // heuristic flagged exactly this shape — the detector's main false-positive source.
        monitor.markInvocationStart();
        monitor.recordFieldAccess("MyClass.counter", 10);
        monitor.markInvocationStart();
        monitor.recordFieldAccess("MyClass.counter", 99);

        assertFalse(monitor.analyzeVisibility().hasIssues(),
                "cross-round variation seen by a single thread is ordinary program behavior, "
                        + "not a visibility issue");
    }

    @Test
    void twoThreadsDifferentValuesInDifferentRoundsIsNotFlagged() throws InterruptedException {
        VisibilityMonitor monitor = new VisibilityMonitor();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("MyClass.handoff", "round1");
        monitor.markInvocationStart();
        recordOnNewThread(monitor, "MyClass.handoff", "round2");

        assertFalse(monitor.analyzeVisibility().hasIssues(),
                "cross-round pairs are ordered by the harness's round happens-before edges "
                        + "even when the observers are different threads");
    }

    @Test
    void disabledDetectorSkipsRecording() throws InterruptedException {
        VisibilityMonitor monitor = new VisibilityMonitor();
        monitor.disable();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("SomeClass.flag", "value-A");
        recordOnNewThread(monitor, "SomeClass.flag", "value-B");

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertFalse(report.hasIssues(), "Disabled monitor should not record any accesses");
    }

    @Test
    void resetClearsState() throws InterruptedException {
        VisibilityMonitor monitor = new VisibilityMonitor();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("A.x", 1);
        recordOnNewThread(monitor, "A.x", 2);

        monitor.reset();

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        assertFalse(report.hasIssues(), "After reset() all state should be cleared");
        assertTrue(report.suspectedFields.isEmpty(), "suspectedFields should be empty after reset");
    }

    @Test
    void reportToStringContainsIssues() throws InterruptedException {
        VisibilityMonitor monitor = new VisibilityMonitor();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("Service.cache", "v1");
        recordOnNewThread(monitor, "Service.cache", "v2");

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();

        String text = report.toString();
        assertNotNull(text);
        assertTrue(text.contains("POTENTIAL VISIBILITY ISSUES"),
                "toString() for a report with issues should contain POTENTIAL VISIBILITY ISSUES");
        assertTrue(text.contains("Service.cache"),
                "toString() should name the suspected field");
    }

    @Test
    void nullSafetyOnDisable() throws InterruptedException {
        VisibilityMonitor monitor = new VisibilityMonitor();
        // disable/enable round-trip should be safe and re-enable recording
        monitor.disable();
        monitor.enable();

        monitor.markInvocationStart();
        monitor.recordFieldAccess("A.field", 1);
        recordOnNewThread(monitor, "A.field", 2);

        VisibilityMonitor.VisibilityReport report = monitor.analyzeVisibility();
        assertTrue(report.hasIssues(), "Monitor should record after re-enable");
    }

    @Test
    void analyze_delegatesToAnalyzeVisibility() throws InterruptedException {
        VisibilityMonitor monitor = new VisibilityMonitor();
        monitor.markInvocationStart();
        monitor.recordFieldAccess("A.field", 1);
        recordOnNewThread(monitor, "A.field", 2);

        VisibilityMonitor.VisibilityReport viaAnalyze = monitor.analyze();
        VisibilityMonitor.VisibilityReport viaAnalyzeVisibility = monitor.analyzeVisibility();

        assertEquals(viaAnalyzeVisibility.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeVisibility.toString(), viaAnalyze.toString());
    }
}
