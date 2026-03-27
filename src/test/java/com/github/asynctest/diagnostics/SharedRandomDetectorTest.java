package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SharedRandomDetector.
 */
public class SharedRandomDetectorTest {

    @Test
    void testSingleThreadRandomUsage() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        detector.registerRandom(random, "single-thread-random");
        
        for (int i = 0; i < 10; i++) {
            random.nextInt();
            detector.recordRandomAccess(random, "single-thread-random", "nextInt");
        }
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single thread usage should not report issues");
    }

    @Test
    void testSharedRandomDetection() throws InterruptedException {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        detector.registerRandom(random, "shared-random");
        
        // Simulate multiple threads accessing the same Random
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                random.nextInt();
                detector.recordRandomAccess(random, "shared-random", "nextInt");
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                random.nextInt();
                detector.recordRandomAccess(random, "shared-random", "nextInt");
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect shared random access");
        assertFalse(report.sharedRandoms.isEmpty(), "Should report shared randoms");
    }

    @Test
    void testMultipleMethodsTracking() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();

        detector.registerRandom(random, "multi-method-random");

        random.nextInt();
        detector.recordRandomAccess(random, "multi-method-random", "nextInt");

        random.nextLong();
        detector.recordRandomAccess(random, "multi-method-random", "nextLong");

        random.nextDouble();
        detector.recordRandomAccess(random, "multi-method-random", "nextDouble");

        SharedRandomDetector.SharedRandomReport report = detector.analyze();

        assertNotNull(report);
        // Single thread, so no issues expected
        assertFalse(report.hasIssues(), "Single thread should not report issues");
        assertTrue(report.randomActivity.containsKey("multi-method-random"), "Should track activity");
    }

    @Test
    void testAutoRegistration() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        // Record without explicit registration - should auto-register
        detector.recordRandomAccess(random, "auto-registered", "nextInt");
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.randomActivity.isEmpty(), "Should track auto-registered random");
    }

    @Test
    void testNullSafety() {
        SharedRandomDetector detector = new SharedRandomDetector();
        
        // Should not throw on null inputs
        detector.registerRandom(null, "null-random");
        detector.recordRandomAccess(null, "null", "nextInt");
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        detector.registerRandom(random, "test-random");
        
        // Simulate shared access
        Thread t1 = new Thread(() -> {
            random.nextInt();
            detector.recordRandomAccess(random, "test-random", "nextInt");
        });
        
        Thread t2 = new Thread(() -> {
            random.nextInt();
            detector.recordRandomAccess(random, "test-random", "nextInt");
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("SHARED RANDOM ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Shared Random Instances"), "Report should mention shared randoms");
    }

    @Test
    void testThreadActivityTracking() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        detector.registerRandom(random, "activity-random");
        
        // Multiple threads
        Thread t1 = new Thread(() -> {
            detector.recordRandomAccess(random, "activity-random", "nextInt");
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordRandomAccess(random, "activity-random", "nextInt");
        });
        
        Thread t3 = new Thread(() -> {
            detector.recordRandomAccess(random, "activity-random", "nextInt");
        });
        
        t1.start();
        t2.start();
        t3.start();
        
        try {
            t1.join();
            t2.join();
            t3.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect 3 threads accessing");
        assertTrue(report.randomActivity.get("activity-random").contains("3 threads"),
                   "Should report 3 threads");
    }
}
