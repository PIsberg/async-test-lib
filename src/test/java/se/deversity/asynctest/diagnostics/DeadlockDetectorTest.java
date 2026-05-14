package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DeadlockDetector.
 */
public class DeadlockDetectorTest {

    @Test
    void noDeadlockReturnsNoIssues() {
        DeadlockDetector detector = new DeadlockDetector();

        DeadlockDetector.DeadlockReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No threads deadlocked — report should have no issues");
    }

    @Test
    void deadlockReportHasIssuesFalseByDefault() {
        DeadlockDetector.DeadlockReport report = new DeadlockDetector.DeadlockReport(false);

        assertFalse(report.hasIssues(), "Report constructed with false should report no issues");
    }

    @Test
    void deadlockReportHasIssuesTrueWhenDeadlocked() {
        DeadlockDetector.DeadlockReport report = new DeadlockDetector.DeadlockReport(true);

        assertTrue(report.hasIssues(), "Report constructed with true should report issues");
    }

    @Test
    void reportToStringContainsDeadlockInfo() {
        DeadlockDetector.DeadlockReport report = new DeadlockDetector.DeadlockReport(true);

        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("DEADLOCK"), "toString() should mention DEADLOCK when deadlocked");
    }

    @Test
    void reportToStringCleanWhenNoIssue() {
        DeadlockDetector.DeadlockReport report = new DeadlockDetector.DeadlockReport(false);

        String text = report.toString();

        assertNotNull(text);
        assertEquals("No deadlocks detected.", text,
                "toString() should return clean message when no deadlock");
    }

    @Test
    void getLockContentionSummaryNotNull() {
        String summary = DeadlockDetector.getLockContentionSummary();

        assertNotNull(summary, "getLockContentionSummary() must not return null");
        assertFalse(summary.isBlank(), "getLockContentionSummary() must not return a blank string");
    }

    @Test
    void hasDeadlockReturnsFalseWithNoDeadlock() {
        boolean result = DeadlockDetector.hasDeadlock();

        assertFalse(result, "hasDeadlock() should return false when no threads are deadlocked");
    }
}
