package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CopyOnWriteCollectionDetector}.
 */
public class CopyOnWriteCollectionDetectorTest {

    @Test
    void testReadHeavyUsageNoIssues() {
        CopyOnWriteCollectionDetector detector = new CopyOnWriteCollectionDetector();
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

        detector.registerCollection(list, "read-heavy-list");

        // 20 reads, 1 write → 4.8% write ratio — below threshold
        for (int i = 0; i < 20; i++) {
            detector.recordRead(list, "read-heavy-list");
        }
        detector.recordWrite(list, "read-heavy-list");

        CopyOnWriteCollectionDetector.CopyOnWriteReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Read-heavy usage should not trigger issues");
    }

    @Test
    void testWriteHeavyUsageDetected() {
        CopyOnWriteCollectionDetector detector = new CopyOnWriteCollectionDetector();
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

        detector.registerCollection(list, "write-heavy-list");

        // 5 reads, 15 writes → 75% write ratio — above 20% threshold
        for (int i = 0; i < 5; i++) {
            detector.recordRead(list, "write-heavy-list");
        }
        for (int i = 0; i < 15; i++) {
            detector.recordWrite(list, "write-heavy-list");
        }

        CopyOnWriteCollectionDetector.CopyOnWriteReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Write-heavy usage should be detected");
        assertFalse(report.writeHeavyViolations.isEmpty(), "Should report write-heavy violation");
    }

    @Test
    void testBelowMinimumWriteCountNoIssue() {
        CopyOnWriteCollectionDetector detector = new CopyOnWriteCollectionDetector();
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

        detector.registerCollection(list, "tiny-list");

        // Only 3 writes, 1 read — below MIN_WRITE_COUNT threshold
        detector.recordRead(list, "tiny-list");
        detector.recordWrite(list, "tiny-list");
        detector.recordWrite(list, "tiny-list");
        detector.recordWrite(list, "tiny-list");

        CopyOnWriteCollectionDetector.CopyOnWriteReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Below minimum write count should not trigger issue");
    }

    @Test
    void testCopyOnWriteArraySetDetected() {
        CopyOnWriteCollectionDetector detector = new CopyOnWriteCollectionDetector();
        CopyOnWriteArraySet<String> set = new CopyOnWriteArraySet<>();

        detector.registerCollection(set, "write-heavy-set");

        // Heavy write scenario
        for (int i = 0; i < 20; i++) {
            detector.recordWrite(set, "write-heavy-set");
        }
        detector.recordRead(set, "write-heavy-set");

        CopyOnWriteCollectionDetector.CopyOnWriteReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Write-heavy CopyOnWriteArraySet should be detected");
    }

    @Test
    void testAutoRegistrationOnFirstAccess() {
        CopyOnWriteCollectionDetector detector = new CopyOnWriteCollectionDetector();
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

        // Access without prior registration
        for (int i = 0; i < 10; i++) {
            detector.recordWrite(list, "auto-list");
        }
        detector.recordRead(list, "auto-list");

        CopyOnWriteCollectionDetector.CopyOnWriteReport report = detector.analyze();
        assertNotNull(report);
        assertTrue(report.collectionActivity.containsKey("auto-list"), "Should auto-register");
    }

    @Test
    void testNullSafety() {
        CopyOnWriteCollectionDetector detector = new CopyOnWriteCollectionDetector();

        assertDoesNotThrow(() -> {
            detector.registerCollection(null, "null-col");
            detector.recordRead(null, "null");
            detector.recordWrite(null, "null");
        });

        CopyOnWriteCollectionDetector.CopyOnWriteReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        CopyOnWriteCollectionDetector detector = new CopyOnWriteCollectionDetector();
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

        detector.registerCollection(list, "report-list");
        for (int i = 0; i < 10; i++) detector.recordWrite(list, "report-list");
        detector.recordRead(list, "report-list");

        CopyOnWriteCollectionDetector.CopyOnWriteReport report = detector.analyze();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("COPY-ON-WRITE COLLECTION ISSUES DETECTED"), "Should contain header");
        assertTrue(text.contains("Write-Heavy Usage"), "Should describe issue type");
        assertTrue(text.contains("ConcurrentHashMap"), "Should suggest fix");
    }

    @Test
    void testActivityReportedForAllCollections() {
        CopyOnWriteCollectionDetector detector = new CopyOnWriteCollectionDetector();
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

        detector.registerCollection(list, "activity-list");
        detector.recordRead(list, "activity-list");
        detector.recordRead(list, "activity-list");
        detector.recordWrite(list, "activity-list");

        CopyOnWriteCollectionDetector.CopyOnWriteReport report = detector.analyze();
        String activity = report.collectionActivity.get("activity-list");

        assertNotNull(activity);
        assertTrue(activity.contains("reads: 2"), "Should report read count");
        assertTrue(activity.contains("writes: 1"), "Should report write count");
    }
}
