package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SharedCollectionDetector}.
 */
public class SharedCollectionDetectorTest {

    @Test
    void testSingleThreadReadNoIssues() {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> list = new ArrayList<>();

        detector.registerCollection(list, "safe-list", "ArrayList");
        detector.recordRead(list, "safe-list", "get");

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread read should not report issues");
    }

    @Test
    void testConcurrentWriteDetection() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> list = new ArrayList<>();

        detector.registerCollection(list, "shared-list", "ArrayList");

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                detector.recordWrite(list, "shared-list", "add");
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                detector.recordWrite(list, "shared-list", "add");
            }
        });

        t1.start(); t2.start();
        t1.join();  t2.join();

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Concurrent writes should be detected");
        assertFalse(report.concurrentWriteViolations.isEmpty(), "Should report write violations");
    }

    @Test
    void testMixedReadWriteFromMultipleThreads() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        HashMap<String, String> map = new HashMap<>();

        detector.registerCollection(map, "shared-map", "HashMap");

        // One writer thread
        Thread writer = new Thread(() -> detector.recordWrite(map, "shared-map", "put"));

        // Multiple reader threads
        Thread r1 = new Thread(() -> detector.recordRead(map, "shared-map", "get"));
        Thread r2 = new Thread(() -> detector.recordRead(map, "shared-map", "get"));
        Thread r3 = new Thread(() -> detector.recordRead(map, "shared-map", "get"));

        writer.start(); writer.join();
        r1.start(); r2.start(); r3.start();
        r1.join(); r2.join(); r3.join();

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Mixed read-write with multiple readers should be detected");
    }

    @Test
    void testAutoRegistrationOnFirstAccess() {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> list = new ArrayList<>();

        // Write without prior registration
        detector.recordWrite(list, "auto-list", "add");

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        assertNotNull(report);
        assertTrue(report.collectionActivity.containsKey("auto-list"), "Should auto-register on first access");
    }

    @Test
    void testNullSafety() {
        SharedCollectionDetector detector = new SharedCollectionDetector();

        assertDoesNotThrow(() -> {
            detector.registerCollection(null, "null-col", "ArrayList");
            detector.recordRead(null, "null", "get");
            detector.recordWrite(null, "null", "add");
        });

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> list = new ArrayList<>();

        detector.registerCollection(list, "report-list", "ArrayList");

        Thread t1 = new Thread(() -> detector.recordWrite(list, "report-list", "add"));
        Thread t2 = new Thread(() -> detector.recordWrite(list, "report-list", "add"));

        t1.start(); t2.start();
        t1.join();  t2.join();

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("SHARED COLLECTION ISSUES DETECTED"), "Should contain header");
        assertTrue(text.contains("Concurrent Write Violations"), "Should describe violation type");
        assertTrue(text.contains("ConcurrentHashMap"), "Should suggest fix");
    }

    @Test
    void testMultipleCollectionsTrackedIndependently() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> sharedList = new ArrayList<>();
        List<String> safeList   = new ArrayList<>();

        detector.registerCollection(sharedList, "shared", "ArrayList");
        detector.registerCollection(safeList, "safe", "ArrayList");

        Thread t1 = new Thread(() -> detector.recordWrite(sharedList, "shared", "add"));
        Thread t2 = new Thread(() -> detector.recordWrite(sharedList, "shared", "add"));
        t1.start(); t2.start();
        t1.join();  t2.join();

        // safeList used by one thread only
        detector.recordWrite(safeList, "safe", "add");

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        assertTrue(report.hasIssues(), "shared list should trigger issues");

        boolean safeListFlagged = report.concurrentWriteViolations.stream()
                .anyMatch(s -> s.contains("safe"));
        assertFalse(safeListFlagged, "safe list should not be flagged");
    }
}
