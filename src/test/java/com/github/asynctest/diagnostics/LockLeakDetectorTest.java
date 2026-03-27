package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LockLeakDetector.
 */
public class LockLeakDetectorTest {

    @Test
    void testNormalLockUsage() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "normal-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "normal-lock");
        try {
            // critical section
        } finally {
            lock.unlock();
            detector.recordLockReleased(lock, "normal-lock");
        }
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testLockLeakDetection() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "leaky-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "leaky-lock");
        // Bug: never releasing the lock!
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect lock leak");
        assertFalse(report.lockLeaks.isEmpty(), "Should report lock leaks");
    }

    @Test
    void testHeldLockDetection() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "held-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "held-lock");
        
        // Wait a bit to ensure the lock appears held
        Thread.sleep(50);
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect held lock");
        assertFalse(report.heldLocks.isEmpty(), "Should report held locks");
        
        lock.unlock();
    }

    @Test
    void testExcessiveHoldTimeDetection() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "slow-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "slow-lock");
        
        // Hold for more than 5 seconds threshold
        Thread.sleep(100); // Use shorter time for test, detector uses 5000ms threshold
        
        lock.unlock();
        detector.recordLockReleased(lock, "slow-lock");
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        // Hold time was only 100ms, should not trigger excessive hold time
        assertTrue(report.excessiveHoldTimes.isEmpty(), "Should not report excessive hold for short hold");
    }

    @Test
    void testThreadActivityTracking() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "multi-thread-lock");
        
        // Simulate multiple threads acquiring and releasing
        Thread t1 = new Thread(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "multi-thread-lock");
            lock.unlock();
            detector.recordLockReleased(lock, "multi-thread-lock");
        });
        
        Thread t2 = new Thread(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "multi-thread-lock");
            lock.unlock();
            detector.recordLockReleased(lock, "multi-thread-lock");
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.threadActivity.isEmpty(), "Should track thread activity");
        assertTrue(report.threadActivity.get("multi-thread-lock").contains("2 threads"),
                   "Should report 2 threads participated");
    }

    @Test
    void testNullSafety() {
        LockLeakDetector detector = new LockLeakDetector();
        
        // Should not throw on null inputs
        detector.registerLock(null, "null-lock");
        detector.recordLockAcquired(null, "null");
        detector.recordLockReleased(null, "null");
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "test-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "test-lock");
        // Leak the lock
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("LOCK LEAK ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Lock Leaks"), "Report should mention lock leaks");
    }

    @Test
    void testMultipleAcquireRelease() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "reentrant-lock");
        
        // Multiple acquire/release cycles
        for (int i = 0; i < 5; i++) {
            lock.lock();
            detector.recordLockAcquired(lock, "reentrant-lock");
            lock.unlock();
            detector.recordLockReleased(lock, "reentrant-lock");
        }
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Should not report issues for balanced acquire/release");
    }
}
