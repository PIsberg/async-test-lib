package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ResourceLeakDetector.
 */
public class ResourceLeakDetectorTest {

    @Test
    void testNormalResourceUsage() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        MockResource resource = new MockResource();
        
        detector.registerResource(resource, "normal-resource", "MockResource");
        detector.recordResourceOpened(resource, "normal-resource");
        detector.recordResourceClosed(resource, "normal-resource");
        
        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testResourceLeakDetection() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        MockResource resource = new MockResource();
        
        detector.registerResource(resource, "leaky-resource", "MockResource");
        detector.recordResourceOpened(resource, "leaky-resource");
        // Bug: never closing the resource!
        
        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect resource leak");
        assertFalse(report.resourceLeaks.isEmpty(), "Should report resource leaks");
    }

    @Test
    void testOpenResourceDetection() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        MockResource resource = new MockResource();
        
        detector.registerResource(resource, "open-resource", "MockResource");
        detector.recordResourceOpened(resource, "open-resource");
        // Resource still open at analysis time
        
        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect open resource");
        assertFalse(report.openResources.isEmpty(), "Should report open resources");
    }

    @Test
    void testMultipleOpenCloseCycles() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        MockResource resource = new MockResource();
        
        detector.registerResource(resource, "multi-cycle-resource", "MockResource");
        
        // Multiple open/close cycles
        for (int i = 0; i < 5; i++) {
            detector.recordResourceOpened(resource, "multi-cycle-resource");
            detector.recordResourceClosed(resource, "multi-cycle-resource");
        }
        
        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Balanced open/close should not report issues");
    }

    @Test
    void testThreadActivityTracking() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        MockResource resource = new MockResource();
        
        detector.registerResource(resource, "multi-thread-resource", "MockResource");
        
        Thread t1 = new Thread(() -> {
            detector.recordResourceOpened(resource, "multi-thread-resource");
            detector.recordResourceClosed(resource, "multi-thread-resource");
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordResourceOpened(resource, "multi-thread-resource");
            detector.recordResourceClosed(resource, "multi-thread-resource");
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.threadActivity.isEmpty(), "Should track thread activity");
        assertTrue(report.threadActivity.get("multi-thread-resource").contains("2 threads"),
                   "Should report 2 threads");
    }

    @Test
    void testNullSafety() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        
        // Should not throw on null inputs
        detector.registerResource(null, "null-resource", "NullResource");
        detector.recordResourceOpened(null, "null");
        detector.recordResourceClosed(null, "null");
        
        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        MockResource resource = new MockResource();
        
        detector.registerResource(resource, "test-resource", "MockResource");
        detector.recordResourceOpened(resource, "test-resource");
        // Leak the resource
        
        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("RESOURCE LEAK ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Resource Leaks"), "Report should mention resource leaks");
    }

    @Test
    void testPartialCloseDetection() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        MockResource resource = new MockResource();
        
        detector.registerResource(resource, "partial-close-resource", "MockResource");
        
        // Open 3 times, close only 2 times
        detector.recordResourceOpened(resource, "partial-close-resource");
        detector.recordResourceOpened(resource, "partial-close-resource");
        detector.recordResourceOpened(resource, "partial-close-resource");
        detector.recordResourceClosed(resource, "partial-close-resource");
        detector.recordResourceClosed(resource, "partial-close-resource");
        
        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect partial close");
        assertFalse(report.resourceLeaks.isEmpty(), "Should report resource leaks");
    }

    // Helper mock resource class
    private static class MockResource implements AutoCloseable {
        @Override
        public void close() throws Exception {
            // Mock implementation
        }
    }
}
