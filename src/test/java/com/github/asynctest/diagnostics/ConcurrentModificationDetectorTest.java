package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConcurrentModificationDetector.
 */
public class ConcurrentModificationDetectorTest {

    @Test
    void testNormalCollectionUsage() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "normal-list");
        detector.recordModification(list, "normal-list", "add");
        list.add("item1");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testConcurrentModificationDetection() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "concurrent-list");
        
        // Simulate iteration started
        detector.recordIterationStarted(list, "concurrent-list");
        
        // Modification during iteration - bug!
        detector.recordModificationDuringIteration(list, "concurrent-list", "add");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect concurrent modification");
        assertFalse(report.concurrentModifications.isEmpty(), "Should report concurrent modifications");
    }

    @Test
    void testConcurrentIterationDetection() throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "iterated-list");
        
        // Use a barrier to ensure both threads iterate at the same time
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(2);
        
        Thread t1 = new Thread(() -> {
            try {
                startLatch.await();
                detector.recordIterationStarted(list, "iterated-list");
                Thread.sleep(10); // Hold iteration
                detector.recordIterationEnded(list, "iterated-list");
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
        
        Thread t2 = new Thread(() -> {
            try {
                startLatch.await();
                detector.recordIterationStarted(list, "iterated-list");
                Thread.sleep(10); // Hold iteration
                detector.recordIterationEnded(list, "iterated-list");
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
        
        t1.start();
        t2.start();
        startLatch.countDown(); // Release both threads simultaneously
        doneLatch.await(); // Wait for both to complete
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect concurrent iterations");
        assertFalse(report.concurrentIterations.isEmpty(), "Should report concurrent iterations");
    }

    @Test
    void testConcurrentMutationDetection() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "mutated-list");
        
        // Simulate multiple threads modifying
        Thread t1 = new Thread(() -> {
            detector.recordModification(list, "mutated-list", "add");
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordModification(list, "mutated-list", "add");
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect concurrent mutations");
        assertFalse(report.concurrentMutations.isEmpty(), "Should report concurrent mutations");
    }

    @Test
    void testIterationLifecycle() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "lifecycle-list");
        
        detector.recordIterationStarted(list, "lifecycle-list");
        // During iteration, activeIterators should be 1
        detector.recordIterationEnded(list, "lifecycle-list");
        // After ending, activeIterators should be 0
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        // No issues if no modifications during iteration
        assertTrue(report.concurrentModifications.isEmpty(), "Should have no concurrent modifications");
    }

    @Test
    void testNullSafety() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        
        // Should not throw on null inputs
        detector.registerCollection(null, "null-collection");
        detector.recordIterationStarted(null, "null");
        detector.recordIterationEnded(null, "null");
        detector.recordModification(null, "null", "add");
        detector.recordModificationDuringIteration(null, "null", "add");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "test-list");
        detector.recordIterationStarted(list, "test-list");
        detector.recordModificationDuringIteration(list, "test-list", "add");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("CONCURRENT MODIFICATION ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Modifications During Iteration"), "Report should mention modifications during iteration");
    }

    @Test
    void testCollectionActivityTracking() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "active-list");
        detector.recordModification(list, "active-list", "add");
        detector.recordModification(list, "active-list", "add");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.collectionActivity.isEmpty(), "Should track collection activity");
        assertTrue(report.collectionActivity.get("active-list").contains("modifications: 2"),
                   "Should report correct modification count");
    }
}
