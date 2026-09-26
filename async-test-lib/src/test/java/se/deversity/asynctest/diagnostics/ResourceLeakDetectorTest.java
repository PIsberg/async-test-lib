package se.deversity.asynctest.diagnostics;

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
        assertTrue(report.threadActivity.stream().filter(a -> a.startsWith("multi-thread-resource: ")).findFirst().orElse("").contains("2 threads"),
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

    /**
     * Two resources may share a name. Each keeps its own thread-activity line; filed under the
     * name, the second resource's line overwrote the first's (#789).
     */
    @Test
    void twoResourcesWithTheSameNameEachKeepTheirThreadActivity() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        Object closed = new Object();
        Object leaked = new Object();
        detector.registerResource(closed, "conn", "Connection");
        detector.registerResource(leaked, "conn", "Connection");
        detector.recordResourceOpened(closed, "conn");
        detector.recordResourceClosed(closed, "conn");
        detector.recordResourceOpened(leaked, "conn");

        String report = detector.analyze().toString();
        assertTrue(report.contains("conn: Connection: 1 threads opened, 1 threads closed, opens: 1, closes: 1"),
                "the closed resource's line survives beside the leaked one's: " + report);
        assertTrue(report.contains("conn: Connection: 1 threads opened, 0 threads closed, opens: 1, closes: 0"),
                "the leaked resource's line survives beside the closed one's: " + report);
    }
}
