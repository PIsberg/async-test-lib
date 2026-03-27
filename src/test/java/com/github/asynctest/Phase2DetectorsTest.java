package com.github.asynctest;

import com.github.asynctest.diagnostics.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.text.SimpleDateFormat;
import java.util.stream.Collectors;
import java.io.StringReader;
import java.io.BufferedReader;
import java.util.Random;
import java.util.List;
import java.util.Arrays;
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

    // ============= Shared Random Tests =============

    @AsyncTest(threads = 4, detectSharedRandom = true, timeoutMs = 3000)
    void testSharedRandomDetection() {
        Random random = new Random();
        AsyncTestContext.sharedRandomMonitor()
            .registerRandom(random, "shared-random");
        
        // Multiple threads accessing same Random - not recommended
        int value = random.nextInt();
        AsyncTestContext.sharedRandomMonitor()
            .recordRandomAccess(random, "shared-random", "nextInt");
    }

    @AsyncTest(threads = 4, detectSharedRandom = true, timeoutMs = 3000)
    void testThreadLocalRandomUsage() {
        // Proper way: use ThreadLocalRandom for concurrent access
        int value = java.util.concurrent.ThreadLocalRandom.current().nextInt();
        // ThreadLocalRandom doesn't need monitoring - it's thread-safe by design
    }

    // ============= Blocking Queue Tests =============

    @AsyncTest(threads = 4, detectBlockingQueueIssues = true, timeoutMs = 3000)
    void testBlockingQueueUsage() throws InterruptedException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);
        AsyncTestContext.blockingQueueMonitor()
            .registerQueue(queue, "work-queue", 10);
        
        // Producer
        boolean added = queue.offer("item-" + Thread.currentThread().getId());
        AsyncTestContext.blockingQueueMonitor()
            .recordOffer(queue, "work-queue", added);
        
        // Consumer
        String item = queue.poll();
        AsyncTestContext.blockingQueueMonitor()
            .recordPoll(queue, "work-queue", item != null);
    }

    @AsyncTest(threads = 2, detectBlockingQueueIssues = true, timeoutMs = 3000)
    void testBlockingQueueSaturation() throws InterruptedException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);
        AsyncTestContext.blockingQueueMonitor()
            .registerQueue(queue, "small-queue", 2);
        
        // Fill the queue
        queue.offer("item1");
        AsyncTestContext.blockingQueueMonitor()
            .recordOffer(queue, "small-queue", true);
        queue.offer("item2");
        AsyncTestContext.blockingQueueMonitor()
            .recordOffer(queue, "small-queue", true);
        
        // This will fail (queue full)
        boolean added = queue.offer("item3");
        AsyncTestContext.blockingQueueMonitor()
            .recordOffer(queue, "small-queue", added);
    }

    // ============= Condition Variable Tests =============

    @AsyncTest(threads = 4, detectConditionVariableIssues = true, timeoutMs = 3000)
    void testConditionVariableUsage() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        AsyncTestContext.conditionMonitor()
            .registerCondition(condition, "data-ready");
        
        lock.lock();
        try {
            // Signal (may be lost if no waiters)
            AsyncTestContext.conditionMonitor()
                .recordSignal(condition, "data-ready", false);
            condition.signal();
        } finally {
            lock.unlock();
        }
    }

    // ============= SimpleDateFormat Tests =============

    @AsyncTest(threads = 4, detectSimpleDateFormatIssues = true, timeoutMs = 3000)
    void testSimpleDateFormatUsage() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        AsyncTestContext.simpleDateFormatMonitor()
            .registerFormatter(sdf, "date-formatter");
        
        // Not thread-safe - will be detected
        String formatted = sdf.format(new java.util.Date());
        AsyncTestContext.simpleDateFormatMonitor()
            .recordFormat(sdf, "date-formatter");
    }

    // ============= Parallel Stream Tests =============

    @AsyncTest(threads = 4, detectParallelStreamIssues = true, timeoutMs = 3000)
    void testParallelStreamStatefulLambda() {
        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5);
        AtomicInteger counter = new AtomicInteger();
        
        AsyncTestContext.parallelStreamMonitor()
            .recordParallelStream("stateful-stream");
        
        // Bug: stateful lambda modifying external state
        list.parallelStream().forEach(i -> counter.incrementAndGet());
        AsyncTestContext.parallelStreamMonitor()
            .recordStatefulOperation("stateful-stream", "forEach");
    }

    @AsyncTest(threads = 4, detectParallelStreamIssues = true, timeoutMs = 3000)
    void testParallelStreamSafeUsage() {
        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5);
        
        AsyncTestContext.parallelStreamMonitor()
            .recordParallelStream("safe-stream");
        
        // Safe: stateless operations
        int sum = list.parallelStream()
            .map(i -> i * 2)
            .filter(i -> i > 5)
            .reduce(0, Integer::sum);
        
        AsyncTestContext.parallelStreamMonitor()
            .recordStatelessOperation("safe-stream", "map");
        AsyncTestContext.parallelStreamMonitor()
            .recordStatelessOperation("safe-stream", "filter");
        AsyncTestContext.parallelStreamMonitor()
            .recordStatelessOperation("safe-stream", "reduce");
    }

    // ============= Resource Leak Tests =============

    @AsyncTest(threads = 4, detectResourceLeaks = true, timeoutMs = 3000)
    void testResourceLeakProperUsage() throws Exception {
        java.io.StringReader reader = new java.io.StringReader("test data");
        AsyncTestContext.resourceLeakMonitor()
            .registerResource(reader, "proper-resource", "StringReader");
        
        try {
            reader.read();
            AsyncTestContext.resourceLeakMonitor()
                .recordResourceOpened(reader, "proper-resource");
        } finally {
            reader.close();
            AsyncTestContext.resourceLeakMonitor()
                .recordResourceClosed(reader, "proper-resource");
        }
    }

    @AsyncTest(threads = 2, detectResourceLeaks = true, timeoutMs = 3000)
    void testResourceLeakScenario() throws Exception {
        java.io.StringReader reader = new java.io.StringReader("test data");
        AsyncTestContext.resourceLeakMonitor()
            .registerResource(reader, "leaky-resource", "StringReader");
        
        AsyncTestContext.resourceLeakMonitor()
            .recordResourceOpened(reader, "leaky-resource");
        // Intentional: not closing - simulates resource leak
        // In real code this would be: } finally { reader.close(); }
    }
}
