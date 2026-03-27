package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SemaphoreMisuseDetector.
 */
public class SemaphoreMisuseDetectorTest {

    @Test
    void testNormalSemaphoreUsage() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(2);
        
        detector.registerSemaphore(semaphore, "resource-pool", 2);
        detector.recordAcquire(semaphore, "resource-pool");
        detector.recordRelease(semaphore, "resource-pool");
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testPermitLeakDetection() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(2);
        
        detector.registerSemaphore(semaphore, "leaky-pool", 2);
        detector.recordAcquire(semaphore, "leaky-pool");
        detector.recordAcquire(semaphore, "leaky-pool");
        detector.recordRelease(semaphore, "leaky-pool");
        // Missing one release - leak!
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect permit leak");
        assertFalse(report.permitLeaks.isEmpty(), "Should report permit leaks");
    }

    @Test
    void testOverReleaseDetection() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(2);
        
        detector.registerSemaphore(semaphore, "over-release-pool", 2);
        detector.recordAcquire(semaphore, "over-release-pool");
        detector.recordRelease(semaphore, "over-release-pool");
        detector.recordRelease(semaphore, "over-release-pool");
        // Extra release!
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect over-release");
        assertFalse(report.overReleases.isEmpty(), "Should report over-releases");
    }

    @Test
    void testUnreleasedPermitsDetection() throws InterruptedException {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(2);
        
        detector.registerSemaphore(semaphore, "unreleased-pool", 2);
        semaphore.acquire(); // Actually acquire a permit
        detector.recordAcquire(semaphore, "unreleased-pool");
        // Never release - permits remain acquired
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect unreleased permits");
        assertFalse(report.unreleasedPermits.isEmpty(), "Should report unreleased permits");
    }

    @Test
    void testThreadActivityTracking() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(3);
        
        detector.registerSemaphore(semaphore, "multi-thread-pool", 3);
        
        // Simulate multiple threads
        Thread t1 = new Thread(() -> {
            detector.recordAcquire(semaphore, "multi-thread-pool");
            detector.recordRelease(semaphore, "multi-thread-pool");
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordAcquire(semaphore, "multi-thread-pool");
            detector.recordRelease(semaphore, "multi-thread-pool");
        });
        
        Thread t3 = new Thread(() -> {
            detector.recordAcquire(semaphore, "multi-thread-pool");
            detector.recordRelease(semaphore, "multi-thread-pool");
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
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.threadActivity.isEmpty(), "Should track thread activity");
        assertTrue(report.threadActivity.get("multi-thread-pool").contains("3 threads"),
                   "Should report 3 threads participated");
    }

    @Test
    void testAutoRegistration() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(1);
        
        // Record without explicit registration - should auto-register
        detector.recordAcquire(semaphore, "auto-registered");
        detector.recordRelease(semaphore, "auto-registered");
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Auto-registered semaphore should work correctly");
    }

    @Test
    void testNullSafety() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        
        // Should not throw on null inputs
        detector.recordAcquire(null, "null-semaphore");
        detector.recordRelease(null, "null-semaphore");
        detector.registerSemaphore(null, "null", 1);
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(1);
        
        detector.registerSemaphore(semaphore, "test-pool", 1);
        detector.recordAcquire(semaphore, "test-pool");
        // Leak one permit
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("SEMAPHORE MISUSE DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Permit Leaks"), "Report should mention permit leaks");
    }
}
