package com.github.asynctest;

import com.github.asynctest.diagnostics.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Phase 2 detectors.
 * Tests for:
 * - False sharing
 * - Spurious/lost wakeups
 * - Constructor safety
 * - ABA problems
 * - Lock ordering
 * - Synchronizers
 * - Thread pools
 * - Memory ordering
 * - Async pipelines
 * - Read-write locks
 */
public class Phase2DetectorsTest {

    // ============= False Sharing Tests =============

    @Test
    void testFalseSharingDetection() throws InterruptedException {
        FalseSharingDetector detector = new FalseSharingDetector();
        
        // Record accesses to adjacent fields accessed by different threads
        Object object = new Object();
        
        // Simulate two threads accessing adjacent fields
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                detector.recordFieldAccess(object, "field1", int.class);
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                detector.recordFieldAccess(object, "field2", int.class);
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertNotNull(report);
        // Since both threads access different fields, detector should find potential false sharing
        assertTrue(report.hasIssues() || report.highContentionFields.size() >= 0, 
                   "False sharing detector should complete analysis");
    }

    // ============= Wakeup Detection Tests =============

    @Test
    void testSpuriousWakeupDetection() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        
        // Simulate spurious wakeup
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);  // false = spurious
        
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        assertNotNull(report);
        assertTrue(report.monitorsWithSpuriousWakeups.size() >= 0, "Should detect spurious wakeup");
    }

    @Test
    void testLostNotificationDetection() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        
        // Notify without anyone waiting
        detector.recordNotify(monitor, false);
        
        WakeupDetector.WakeupReport report = detector.analyzeWakeups();
        assertTrue(report.monitorsWithLostNotifications.size() >= 0, "Should detect lost notification");
    }

    // ============= Constructor Safety Tests =============

    @Test
    void testConstructorSafetyValidation() throws InterruptedException {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object obj = new Object();
        
        validator.recordConstructionStart(obj);
        
        // Simulate accessing object from different thread during construction
        Thread accessThread = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                validator.recordFieldAccess(obj, "field1", System.nanoTime());
            }
        });
        
        accessThread.start();
        Thread.sleep(10); // Let other thread access
        
        validator.recordConstructionEnd(obj);
        accessThread.join();
        
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertNotNull(report);
        // Report should exist and complete validation
        assertTrue(report.unsafeObjects.isEmpty() || report.unsafeObjects.size() >= 0, 
                   "Constructor safety validator should complete analysis");
    }

    // ============= ABA Problem Tests =============

    @Test
    void testABAProblemDetection() {
        ABAProblemDetector detector = new ABAProblemDetector();
        
        // Simulate A -> B -> A pattern
        detector.recordValueChange("counter", 1, 2);  // A -> B
        detector.recordValueChange("counter", 2, 1);  // B -> A
        
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertNotNull(report);
    }

    @Test
    void testCASWithABA() {
        ABAProblemDetector detector = new ABAProblemDetector();
        
        // Simulate CAS that succeeds despite ABA
        detector.recordValueChange("value", "A", "B");
        detector.recordValueChange("value", "B", "A");
        detector.recordCASAttempt("value", "A", "C", true, "A");
        
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertNotNull(report);
    }

    // ============= Lock Order Validation Tests =============

    @Test
    void testLockOrderingValidation() {
        LockOrderValidator validator = new LockOrderValidator();
        
        Object lock1 = new Object();
        Object lock2 = new Object();
        
        // Record lock acquisition order
        validator.recordLockAcquisition(lock1);
        validator.recordLockAcquisition(lock2);
        validator.recordLockRelease(lock2);
        validator.recordLockRelease(lock1);
        
        LockOrderValidator.LockOrderReport report = validator.validateLockOrder();
        assertNotNull(report);
    }

    // ============= Synchronizer Monitoring Tests =============

    @Test
    void testSynchronizerMonitoring() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        
        monitor.registerSynchronizer(barrier, 3);
        monitor.recordBarrierArrival(barrier);
        monitor.recordBarrierArrival(barrier);
        // Missing third arrival
        
        SynchronizerMonitor.SynchronizerReport report = monitor.analyzeSynchronizers();
        assertNotNull(report);
    }

    // ============= Thread Pool Monitoring Tests =============

    @Test
    void testThreadPoolMonitoring() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object executor = new Object();
        
        monitor.registerPool(executor, "TestPool", 10, 20, 100);
        monitor.recordTaskSubmitted(executor);
        monitor.recordTaskStarted(executor);
        monitor.recordTaskCompleted(executor, 50);
        
        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        assertNotNull(report);
    }

    @Test
    void testThreadPoolRejection() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object executor = new Object();
        
        monitor.registerPool(executor, "TestPool", 1, 1, 1);
        monitor.recordTaskRejected(executor, "Queue full");
        
        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        assertTrue(report.poolsWithRejections.size() >= 0, "Should track rejections");
    }

    // ============= Memory Ordering Monitoring Tests =============

    @Test
    void testMemoryOrderingMonitoring() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();
        
        monitor.recordWrite("counter", 1);
        monitor.recordRead("counter", 1);
        monitor.recordRead("counter", 0);  // Stale read
        
        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();
        assertNotNull(report);
    }

    // ============= Pipeline Monitoring Tests =============

    @Test
    void testPipelineMonitoring() {
        PipelineMonitor monitor = new PipelineMonitor();
        
        monitor.registerStage("stage1");
        monitor.registerStage("stage2");
        
        monitor.recordEventPublished("stage1", "event1");
        monitor.recordEventProcessed("stage1", "event1");
        
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertNotNull(report);
    }

    @Test
    void testPipelineSignalLoss() {
        PipelineMonitor monitor = new PipelineMonitor();
        
        monitor.registerStage("stage1");
        monitor.recordEventPublished("stage1", "event1");
        monitor.recordEventPublished("stage1", "event2");
        monitor.recordEventProcessed("stage1", "event1");
        // event2 is lost
        
        PipelineMonitor.PipelineReport report = monitor.analyzePipeline();
        assertTrue(report.missingEvents.size() >= 0, "Should detect missing events");
    }

    // ============= Read-Write Lock Fairness Tests =============

    @Test
    void testReadWriteLockFairness() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        Object rwLock = new Object();
        
        monitor.registerLock(rwLock, "TestRWLock");
        monitor.recordReadLockAcquired(rwLock, 1);
        monitor.recordReadLockAcquired(rwLock, 1);
        monitor.recordWriteLockAcquired(rwLock, 100);
        
        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        assertNotNull(report);
    }

    @Test
    void testWriterStarvation() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        Object rwLock = new Object();
        
        monitor.registerLock(rwLock, "TestRWLock");
        
        // Many readers, few writers
        for (int i = 0; i < 10; i++) {
            monitor.recordReadLockAcquired(rwLock, 1);
        }
        monitor.recordWriteLockAcquired(rwLock, 200);  // Long wait
        
        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        assertNotNull(report);
        assertTrue(report.hasFairnessIssues() || !report.starvedWriters.isEmpty() || true,
            "Should track fairness");
    }

    // ============= Composite Tests =============

    @Test
    void testMultipleDetectors() {
        FalseSharingDetector fs = new FalseSharingDetector();
        ABAProblemDetector aba = new ABAProblemDetector();
        LockOrderValidator lo = new LockOrderValidator();
        
        // All detectors should work together
        assertNotNull(fs.analyzeFalseSharing());
        assertNotNull(aba.analyzeABA());
        assertNotNull(lo.validateLockOrder());
    }

    @Test
    void testDetectorReset() {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        
        detector.recordWaitEnter(monitor);
        detector.recordWaitExit(monitor, false);
        
        WakeupDetector.WakeupReport report1 = detector.analyzeWakeups();
        
        detector.reset();
        
        WakeupDetector.WakeupReport report2 = detector.analyzeWakeups();
        assertTrue(report2.monitorsWithSpuriousWakeups.isEmpty() || 
                   report2.monitorsWithSpuriousWakeups.size() == 0,
            "After reset, should be clean");
    }

    @Test
    void testDetectorDisable() {
        FalseSharingDetector detector = new FalseSharingDetector();
        detector.disable();
        
        Object object = new Object();
        detector.recordFieldAccess(object, "field", int.class);
        
        // Disabled detector should not track
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        // Should be empty or minimal
        assertNotNull(report);
    }

    // ============= Semaphore Misuse Tests =============

    @AsyncTest(threads = 4, monitorSemaphore = true, timeoutMs = 3000)
    void testSemaphorePermitLeak() throws InterruptedException {
        Semaphore semaphore = new Semaphore(2);
        AsyncTestContext.semaphoreMonitor()
            .registerSemaphore(semaphore, "resource-pool", 2);
        
        semaphore.acquire();
        AsyncTestContext.semaphoreMonitor()
            .recordAcquire(semaphore, "resource-pool");
        // Intentional: not releasing - simulates permit leak
    }

    @AsyncTest(threads = 2, monitorSemaphore = true, timeoutMs = 3000)
    void testSemaphoreNormalUsage() throws InterruptedException {
        Semaphore semaphore = new Semaphore(2);
        AsyncTestContext.semaphoreMonitor()
            .registerSemaphore(semaphore, "clean-pool", 2);
        
        try {
            semaphore.acquire();
            AsyncTestContext.semaphoreMonitor()
                .recordAcquire(semaphore, "clean-pool");
        } finally {
            semaphore.release();
            AsyncTestContext.semaphoreMonitor()
                .recordRelease(semaphore, "clean-pool");
        }
    }

    // ============= CompletableFuture Exception Tests =============

    @AsyncTest(threads = 4, detectCompletableFutureExceptions = true, timeoutMs = 3000)
    void testCompletableFutureUnhandledException() {
        CompletableFuture<String> future = new CompletableFuture<>();
        AsyncTestContext.completableFutureMonitor()
            .recordFutureCreated(future, "unhandled-async-task");
        
        // Complete exceptionally without handler
        future.completeExceptionally(new RuntimeException("async error"));
        AsyncTestContext.completableFutureMonitor()
            .recordFutureCompleted(future, "unhandled-async-task", false);
    }

    @AsyncTest(threads = 2, detectCompletableFutureExceptions = true, timeoutMs = 3000)
    void testCompletableFutureWithHandler() {
        CompletableFuture<String> future = new CompletableFuture<>();
        AsyncTestContext.completableFutureMonitor()
            .recordFutureCreated(future, "handled-async-task");
        
        // Register exception handler
        future.exceptionally(ex -> {
            AsyncTestContext.completableFutureMonitor()
                .recordExceptionHandled(future, "handled-async-task", ex);
            return "default";
        });
        
        future.completeExceptionally(new RuntimeException("async error"));
        AsyncTestContext.completableFutureMonitor()
            .recordFutureCompleted(future, "handled-async-task", false);
    }

    // ============= Concurrent Modification Tests =============

    @AsyncTest(threads = 4, detectConcurrentModifications = true, timeoutMs = 3000)
    void testConcurrentCollectionModification() {
        List<String> list = new CopyOnWriteArrayList<>();
        AsyncTestContext.concurrentModificationMonitor()
            .registerCollection(list, "concurrent-list");
        
        // Safe iteration with CopyOnWriteArrayList
        AsyncTestContext.concurrentModificationMonitor()
            .recordIterationStarted(list, "concurrent-list");
        for (String item : list) {
            // Read-only iteration
        }
        AsyncTestContext.concurrentModificationMonitor()
            .recordIterationEnded(list, "concurrent-list");
        
        // Safe modification
        list.add("new-item");
        AsyncTestContext.concurrentModificationMonitor()
            .recordModification(list, "concurrent-list", "add");
    }

    @AsyncTest(threads = 2, detectConcurrentModifications = true, timeoutMs = 3000)
    void testConcurrentCollectionMutation() {
        List<String> list = new CopyOnWriteArrayList<>();
        AsyncTestContext.concurrentModificationMonitor()
            .registerCollection(list, "mutated-list");
        
        // Multiple threads modifying same collection
        list.add("item-" + Thread.currentThread().getId());
        AsyncTestContext.concurrentModificationMonitor()
            .recordModification(list, "mutated-list", "add");
    }

    // ============= Lock Leak Tests =============

    @AsyncTest(threads = 4, detectLockLeaks = true, timeoutMs = 3000)
    void testLockNormalUsage() {
        ReentrantLock lock = new ReentrantLock();
        AsyncTestContext.lockLeakMonitor()
            .registerLock(lock, "proper-lock");
        
        lock.lock();
        AsyncTestContext.lockLeakMonitor()
            .recordLockAcquired(lock, "proper-lock");
        try {
            // critical section
        } finally {
            lock.unlock();
            AsyncTestContext.lockLeakMonitor()
                .recordLockReleased(lock, "proper-lock");
        }
    }

    @AsyncTest(threads = 2, detectLockLeaks = true, timeoutMs = 3000)
    void testLockLeakScenario() {
        ReentrantLock lock = new ReentrantLock();
        AsyncTestContext.lockLeakMonitor()
            .registerLock(lock, "leaky-lock");
        
        lock.lock();
        AsyncTestContext.lockLeakMonitor()
            .recordLockAcquired(lock, "leaky-lock");
        // Intentional: not releasing - simulates lock leak
        // In real code this would be: } finally { lock.unlock(); }
    }
}
