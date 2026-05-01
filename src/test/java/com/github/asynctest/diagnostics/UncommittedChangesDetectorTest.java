package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UncommittedChangesDetector.
 */
public class UncommittedChangesDetectorTest {

    @Test
    void testDetectorAnalysis() {
        UncommittedChangesDetector detector = new UncommittedChangesDetector();
        UncommittedChangesDetector.UncommittedChangesReport report = detector.analyze();
        
        assertNotNull(report);
        // Since we are running in a repo where we are currently making changes,
        // it's possible it has issues. We can't strictly assert false for hasIssues().
        // But we can check that it doesn't throw and toString() works.
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("Uncommitted repository changes detected") || reportStr.contains("No uncommitted changes detected"));
    }

    @Test
    void testReportHasIssues() {
        UncommittedChangesDetector.UncommittedChangesReport report = new UncommittedChangesDetector.UncommittedChangesReport();
        assertFalse(report.hasIssues());
        
        report.uncommittedFiles.add("ModifiedFile.java [M]");
        assertTrue(report.hasIssues());
        
        report.uncommittedFiles.clear();
        report.untrackedFiles.add("NewFile.java");
        assertTrue(report.hasIssues());
        
        report.untrackedFiles.clear();
        report.error = "Git error";
        assertTrue(report.hasIssues());
    }

    @Test
    void testReportToString() {
        UncommittedChangesDetector.UncommittedChangesReport report = new UncommittedChangesDetector.UncommittedChangesReport();
        report.uncommittedFiles.add("ModifiedFile.java [M]");
        report.untrackedFiles.add("NewFile.java");
        
        String reportStr = report.toString();
        assertTrue(reportStr.contains("Uncommitted files"));
        assertTrue(reportStr.contains("ModifiedFile.java [M]"));
        assertTrue(reportStr.contains("Untracked files"));
        assertTrue(reportStr.contains("NewFile.java"));
        assertTrue(reportStr.contains("Recommended fix"));
    }
}
