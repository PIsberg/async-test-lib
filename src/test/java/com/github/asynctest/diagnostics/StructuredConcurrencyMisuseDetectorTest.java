package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StructuredConcurrencyMisuseDetector}.
 */
class StructuredConcurrencyMisuseDetectorTest {

    private StructuredConcurrencyMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StructuredConcurrencyMisuseDetector();
    }

    // ---- Happy path ----

    @Test
    void noIssues_whenScopeProperlyOpenedJoinedAndClosed() {
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        detector.recordJoinCalled(scopeId);
        detector.recordResultAccessed(scopeId);
        detector.recordScopeClosed(scopeId);

        var report = detector.analyze();

        assertFalse(report.hasIssues(), "Expected no issues for correct usage: " + report);
        assertTrue(report.getUnclosedScopes().isEmpty());
        assertTrue(report.getClosedWithoutJoin().isEmpty());
        assertTrue(report.getResultAccessedBeforeJoin().isEmpty());
        assertTrue(report.getEmptyScopes().isEmpty());
    }

    @Test
    void noIssues_forMultipleCorrectScopes() {
        for (int i = 0; i < 5; i++) {
            String id = detector.recordScopeOpened("ShutdownOnFailure");
            detector.recordSubtaskForked(id);
            detector.recordSubtaskForked(id);
            detector.recordJoinCalled(id);
            detector.recordResultAccessed(id);
            detector.recordResultAccessed(id);
            detector.recordScopeClosed(id);
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no issues: " + report);
    }

    // ---- Unclosed scope ----

    @Test
    void detectsUnclosedScope_whenScopeNeverClosed() {
        String scopeId = detector.recordScopeOpened("ShutdownOnSuccess");
        detector.recordSubtaskForked(scopeId);
        detector.recordJoinCalled(scopeId);
        // close() is never called

        var report = detector.analyze();

        assertTrue(report.hasIssues());
        assertFalse(report.getUnclosedScopes().isEmpty(),
            "Should detect unclosed scope");
        assertTrue(report.getUnclosedScopes().get(0).contains("ShutdownOnSuccess"));
    }

    // ---- Closed without join ----

    @Test
    void detectsClosedWithoutJoin_whenJoinSkipped() {
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        // join() never called
        detector.recordScopeClosed(scopeId);

        var report = detector.analyze();

        assertTrue(report.hasIssues());
        assertFalse(report.getClosedWithoutJoin().isEmpty(),
            "Should detect scope closed without join");
        String issue = report.getClosedWithoutJoin().get(0);
        assertTrue(issue.contains("ShutdownOnFailure"), issue);
        assertTrue(issue.contains("join"), issue);
    }

    // ---- Result accessed before join ----

    @Test
    void detectsResultAccessedBeforeJoin_whenGetCalledBeforeJoin() {
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        // access result WITHOUT calling join first
        detector.recordResultAccessed(scopeId);
        detector.recordJoinCalled(scopeId);
        detector.recordScopeClosed(scopeId);

        var report = detector.analyze();

        assertTrue(report.hasIssues());
        assertFalse(report.getResultAccessedBeforeJoin().isEmpty(),
            "Should detect result accessed before join");
    }

    @Test
    void noViolation_whenResultAccessedAfterJoin() {
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        detector.recordJoinCalled(scopeId);
        detector.recordResultAccessed(scopeId); // correct order
        detector.recordScopeClosed(scopeId);

        var report = detector.analyze();

        assertTrue(report.getResultAccessedBeforeJoin().isEmpty(),
            "Should not flag result access after join");
    }

    // ---- Empty scope ----

    @Test
    void detectsEmptyScope_whenNoSubtasksForked() {
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        // No subtask forked
        detector.recordJoinCalled(scopeId);
        detector.recordScopeClosed(scopeId);

        var report = detector.analyze();

        assertFalse(report.getEmptyScopes().isEmpty(),
            "Should detect scope with no forked subtasks");
    }

    @Test
    void noEmptyScope_whenSubtaskForked() {
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        detector.recordJoinCalled(scopeId);
        detector.recordScopeClosed(scopeId);

        var report = detector.analyze();

        assertTrue(report.getEmptyScopes().isEmpty());
    }

    // ---- Multiple issues combined ----

    @Test
    void detectsMultipleIssues_simultaneously() {
        // Scope 1: closed without join + has subtasks
        String id1 = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(id1);
        detector.recordScopeClosed(id1);

        // Scope 2: result accessed before join
        String id2 = detector.recordScopeOpened("ShutdownOnSuccess");
        detector.recordSubtaskForked(id2);
        detector.recordResultAccessed(id2); // before join!
        detector.recordJoinCalled(id2);
        detector.recordScopeClosed(id2);

        // Scope 3: never closed
        String id3 = detector.recordScopeOpened("Custom");
        detector.recordSubtaskForked(id3);
        detector.recordJoinCalled(id3);
        // not closed

        var report = detector.analyze();

        assertTrue(report.hasIssues());
        assertFalse(report.getClosedWithoutJoin().isEmpty());
        assertFalse(report.getResultAccessedBeforeJoin().isEmpty());
        assertFalse(report.getUnclosedScopes().isEmpty());
    }

    // ---- Nesting depth ----

    @Test
    void tracksMaxNestingDepth() {
        String outer = detector.recordScopeOpened("Outer");
        detector.recordSubtaskForked(outer);
        detector.recordJoinCalled(outer);

        String inner = detector.recordScopeOpened("Inner"); // depth = 2 on same thread
        detector.recordSubtaskForked(inner);
        detector.recordJoinCalled(inner);
        detector.recordScopeClosed(inner);

        detector.recordScopeClosed(outer);

        var report = detector.analyze();
        assertTrue(report.getMaxNestingDepth() >= 1,
            "Max nesting depth should be at least 1");
    }

    // ---- toString ----

    @Test
    void toString_containsUsefulInfo_whenIssuesDetected() {
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        // skip join, close anyway
        detector.recordScopeClosed(scopeId);

        var report = detector.analyze();
        String str = report.toString();

        assertTrue(str.contains("join"), str);
        assertTrue(str.contains("LEARNING"), str);
    }

    @Test
    void toString_isClean_whenNoIssues() {
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        detector.recordJoinCalled(scopeId);
        detector.recordScopeClosed(scopeId);

        var report = detector.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.toString().contains("No structured concurrency issues"));
    }
}
