package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InterruptMonitor.
 */
public class InterruptMonitorTest {

    @Test
    void noEventsReturnNoIssues() {
        InterruptMonitor monitor = new InterruptMonitor();

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No events recorded — should report no issues");
        assertTrue(report.ignoredInterrupts.isEmpty());
        assertTrue(report.repeatedIgnoredInterrupts.isEmpty());
        assertTrue(report.blockingWithoutHandling.isEmpty());
    }

    @Test
    void interruptExceptionWithRestoreNoIssue() {
        InterruptMonitor monitor = new InterruptMonitor();

        monitor.recordInterruptException(new InterruptedException("test"));
        monitor.recordInterruptRestored();

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();

        assertNotNull(report);
        // The interrupt was restored — ignoredInterrupts should be empty
        assertTrue(report.ignoredInterrupts.isEmpty(),
                "Interrupt that was restored should not appear in ignoredInterrupts");
    }

    @Test
    void interruptExceptionWithoutRestoreDetected() {
        InterruptMonitor monitor = new InterruptMonitor();

        monitor.recordInterruptException(new InterruptedException("swallowed"));
        // deliberately no restore

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();

        assertTrue(report.hasIssues(), "Swallowed interrupt must be flagged");
        assertFalse(report.ignoredInterrupts.isEmpty(),
                "ignoredInterrupts should contain the un-restored interrupt");
    }

    @Test
    void ignoredExceptionDetected() {
        InterruptMonitor monitor = new InterruptMonitor();

        monitor.recordIgnoredException("catch block with empty body");

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();

        assertTrue(report.hasIssues(), "Explicitly recorded ignored exception must be flagged");
        assertFalse(report.ignoredInterrupts.isEmpty(),
                "ignoredInterrupts must contain the explicitly ignored description");
    }

    @Test
    void blockingWithoutHandlingDetected() {
        InterruptMonitor monitor = new InterruptMonitor();

        monitor.recordBlockingOperationWithoutInterruptHandling("Queue.take()");

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();

        assertTrue(report.hasIssues(), "Blocking call without interrupt handling must be flagged");
        assertFalse(report.blockingWithoutHandling.isEmpty(),
                "blockingWithoutHandling must contain the recorded operation");
        assertTrue(report.blockingWithoutHandling.stream().anyMatch(s -> s.contains("Queue.take()")),
                "The operation name should appear in the report");
    }

    @Test
    void nullInterruptedExceptionHandled() {
        InterruptMonitor monitor = new InterruptMonitor();

        // recordInterruptException(null) must not throw
        assertDoesNotThrow(() -> monitor.recordInterruptException(null));

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();
        assertNotNull(report);
    }

    @Test
    void disabledDetectorSkipsRecording() {
        InterruptMonitor monitor = new InterruptMonitor();
        monitor.disable();

        monitor.recordInterruptException(new InterruptedException("ignored by disabling"));
        monitor.recordIgnoredException("suppressed");
        monitor.recordBlockingOperationWithoutInterruptHandling("lock.lock()");

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();

        assertFalse(report.hasIssues(), "Disabled monitor must not record any events");
    }

    @Test
    void reportToStringContainsIssueInfo() {
        InterruptMonitor monitor = new InterruptMonitor();

        monitor.recordInterruptException(new InterruptedException("not restored"));
        // no restore

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("INTERRUPT HANDLING ISSUES"), "toString() should contain the issue header");
        assertTrue(text.contains("interrupt"), "toString() should describe the interrupt problem");
    }

    @Test
    void analyze_delegatesToAnalyzeInterruptHandling() {
        InterruptMonitor monitor = new InterruptMonitor();
        monitor.recordInterruptException(new InterruptedException("not restored"));

        InterruptMonitor.InterruptReport viaAnalyze = monitor.analyze();
        InterruptMonitor.InterruptReport viaAnalyzeInterruptHandling = monitor.analyzeInterruptHandling();

        assertEquals(viaAnalyzeInterruptHandling.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeInterruptHandling.toString(), viaAnalyze.toString());
    }
}
