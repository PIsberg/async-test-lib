package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StringBuilderDetector}.
 */
public class StringBuilderDetectorTest {

    @Test
    void testSingleThreadUsageNoIssues() {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder();

        detector.registerBuilder(sb, "single-thread-builder");
        sb.append("hello");
        detector.recordAppend(sb, "single-thread-builder");
        sb.append(" world");
        detector.recordAppend(sb, "single-thread-builder");
        detector.recordRead(sb, "single-thread-builder");

        StringBuilderDetector.StringBuilderReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread usage should not report issues");
    }

    @Test
    void testSharedAppendDetected() throws InterruptedException {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder();

        detector.registerBuilder(sb, "shared-builder");

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                sb.append("a");
                detector.recordAppend(sb, "shared-builder");
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                sb.append("b");
                detector.recordAppend(sb, "shared-builder");
            }
        });

        t1.start(); t2.start();
        t1.join();  t2.join();

        StringBuilderDetector.StringBuilderReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Shared append should be detected");
        assertFalse(report.sharedBuilderViolations.isEmpty(), "Should report shared builder");
    }

    @Test
    void testInsertRecording() {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder("world");

        detector.registerBuilder(sb, "insert-builder");
        sb.insert(0, "hello ");
        detector.recordInsert(sb, "insert-builder");

        StringBuilderDetector.StringBuilderReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread insert should not report issues");
    }

    @Test
    void testDeleteRecording() {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder("hello world");

        detector.registerBuilder(sb, "delete-builder");
        sb.delete(5, 11);
        detector.recordDelete(sb, "delete-builder");

        StringBuilderDetector.StringBuilderReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread delete should not report issues");
    }

    @Test
    void testReplaceRecording() {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder("hello world");

        detector.registerBuilder(sb, "replace-builder");
        sb.replace(6, 11, "java");
        detector.recordReplace(sb, "replace-builder");

        StringBuilderDetector.StringBuilderReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread replace should not report issues");
    }

    @Test
    void testErrorTracking() {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder();

        detector.registerBuilder(sb, "error-builder");
        detector.recordAppend(sb, "error-builder");
        detector.recordError(sb, "error-builder", "StringIndexOutOfBoundsException");

        StringBuilderDetector.StringBuilderReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.builderErrors.isEmpty(), "Should track errors");
    }

    @Test
    void testNullSafety() {
        StringBuilderDetector detector = new StringBuilderDetector();

        assertDoesNotThrow(() -> {
            detector.registerBuilder(null, "null-builder");
            detector.recordAppend(null, "null");
            detector.recordInsert(null, "null");
            detector.recordDelete(null, "null");
            detector.recordReplace(null, "null");
            detector.recordRead(null, "null");
            detector.recordError(null, "null", "error");
        });

        StringBuilderDetector.StringBuilderReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testAutoRegistrationOnFirstAccess() {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder();

        // Access without explicit registration
        detector.recordAppend(sb, "auto-builder");

        StringBuilderDetector.StringBuilderReport report = detector.analyze();
        assertNotNull(report);
        assertTrue(report.builderActivity.containsKey("auto-builder"), "Should auto-register on first access");
    }

    @Test
    void testReportToString() throws InterruptedException {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder();

        detector.registerBuilder(sb, "report-builder");

        Thread t1 = new Thread(() -> detector.recordAppend(sb, "report-builder"));
        Thread t2 = new Thread(() -> detector.recordAppend(sb, "report-builder"));
        t1.start(); t2.start();
        t1.join();  t2.join();

        StringBuilderDetector.StringBuilderReport report = detector.analyze();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("STRING BUILDER ISSUES DETECTED"), "Should contain header");
        assertTrue(text.contains("Shared StringBuilder Mutations"), "Should describe mutation issue");
        assertTrue(text.contains("ThreadLocal"), "Should suggest ThreadLocal fix");
    }

    @Test
    void testActivityCountsAllOperations() {
        StringBuilderDetector detector = new StringBuilderDetector();
        StringBuilder sb = new StringBuilder();

        detector.registerBuilder(sb, "all-ops-builder");
        detector.recordAppend(sb, "all-ops-builder");
        detector.recordAppend(sb, "all-ops-builder");
        detector.recordInsert(sb, "all-ops-builder");
        detector.recordDelete(sb, "all-ops-builder");
        detector.recordReplace(sb, "all-ops-builder");
        detector.recordRead(sb, "all-ops-builder");

        StringBuilderDetector.StringBuilderReport report = detector.analyze();
        String activity = report.builderActivity.get("all-ops-builder");

        assertNotNull(activity);
        // writes = 5 (2 appends + 1 insert + 1 delete + 1 replace)
        assertTrue(activity.contains("writes: 5"), "Should count all write operations");
        assertTrue(activity.contains("reads: 1"), "Should count read operations");
    }
}
