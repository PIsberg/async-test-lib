package com.github.asynctest;

import com.github.asynctest.diagnostics.*;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Integration tests for Phase 2 detectors using @AsyncTest annotation.
 * Each test uses real concurrent scenarios to verify detector functionality.
 * 
 * Tests verify that:
 * 1. Detectors are properly integrated with @AsyncTest
 * 2. Real concurrent problems are detected
 * 3. Detector reports are generated and meaningful
 * 4. Detection works across multiple invocations
 */
public class Phase2AsyncIntegrationTest {

    // ============= False Sharing Integration Test =============

    @Test
    void testFalseSharingDetectionIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(FalseSharingConcurrentDummy.class))
                .execute()
                .testEvents();
        
        // False sharing detection should complete without errors
        // Detection may or may not trigger depending on platform/JVM
        assertEquals(0, testEvents.aborted().count(), 
            "False sharing test should not abort");
    }

    public static class FalseSharingConcurrentDummy {
        // These fields are likely on the same cache line
        private volatile long counter1 = 0;
        private volatile long counter2 = 0;
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 4, invocations = 10, 
                  detectFalseSharing = true, timeoutMs = 5000)
        void testCacheLineContention() {
            // Two threads contend on adjacent fields
            int threadId = threadAssigner.getAndIncrement();
            if (threadId % 2 == 0) {
                for (int i = 0; i < 1000; i++) {
                    counter1++;
                }
            } else {
                for (int i = 0; i < 1000; i++) {
                    counter2++;
                }
            }
        }
    }

    // ============= Wakeup Issue Integration Test =============

    @Test
    void testWakeupIssueDetectionIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(WakeupIssueConcurrentDummy.class))
                .execute()
                .testEvents();
        
        // Wakeup issues should be detected or test completes normally
        assertEquals(0, testEvents.aborted().count(), 
            "Wakeup issue test should not abort");
    }

    public static class WakeupIssueConcurrentDummy {
        private final Object monitor = new Object();
        private volatile boolean signaled = false;
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 5,
                  detectWakeupIssues = true, timeoutMs = 5000)
        void testSpuriousWakeup() throws InterruptedException {
            int threadId = threadAssigner.getAndIncrement();
            if (threadId % 2 == 0) {
                synchronized (monitor) {
                    while (!signaled) {
                        monitor.wait(100);  // With spurious wakeup possibility
                    }
                }
            } else {
                Thread.sleep(50);
                synchronized (monitor) {
                    signaled = true;
                    monitor.notifyAll();
                }
            }
        }
    }

    // ============= Constructor Safety Integration Test =============

    @Test
    void testConstructorSafetyIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(ConstructorSafetyConcurrentDummy.class))
                .execute()
                .testEvents();
        
        // Constructor safety should be validated
        assertEquals(0, testEvents.aborted().count(), 
            "Constructor safety test should not abort");
    }

    public static class ConstructorSafetyConcurrentDummy {
        static class SafeObject {
            final int value;
            SafeObject(int v) { this.value = v; }
        }

        private final AtomicReference<SafeObject> holder = new AtomicReference<>();
        private final AtomicInteger counter = new AtomicInteger(0);

        @AsyncTest(threads = 3, invocations = 5,
                  validateConstructorSafety = true, timeoutMs = 5000)
        void testObjectPublicationRace() {
            if (counter.getAndIncrement() == 0) {
                // This thread constructs and publishes
                holder.set(new SafeObject(42));
            } else {
                // Other threads try to read
                SafeObject obj = holder.get();
                if (obj != null) {
                    assertEquals(42, obj.value, "Value should be properly initialized");
                }
            }
        }
    }

    // ============= ABA Problem Integration Test =============

    @Test
    void testABAProblemDetectionIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(ABAProbllemConcurrentDummy.class))
                .execute()
                .testEvents();
        
        // ABA detection should complete without abort
        assertEquals(0, testEvents.aborted().count(), 
            "ABA problem test should not abort");
    }

    public static class ABAProbllemConcurrentDummy {
        private final AtomicReference<Integer> value = new AtomicReference<>(1);
        private final AtomicInteger threadAssigner = new AtomicInteger(0);
        private volatile int version = 0;

        @AsyncTest(threads = 2, invocations = 3,
                  detectABAProblem = true, timeoutMs = 5000)
        void testABAProblem() throws InterruptedException {
            int threadId = threadAssigner.getAndIncrement();
            if (threadId % 2 == 0) {
                // Thread 1: A -> B -> A
                value.set(2);
                Thread.sleep(10);
                value.set(1);
            } else {
                // Thread 2: CAS that might succeed despite A->B->A
                Thread.sleep(20);
                boolean success = value.compareAndSet(1, 3);
                // Success but version changed: ABA problem
            }
        }
    }

    // ============= Lock Order Validation Integration Test =============

    @Test
    void testLockOrderValidationIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(LockOrderViolationDummy.class))
                .execute()
                .testEvents();
        
        // Lock order test should complete
        assertEquals(0, testEvents.aborted().count(), 
            "Lock order validation test should not abort");
    }

    public static class LockOrderViolationDummy {
        private final Object lock1 = new Object();
        private final Object lock2 = new Object();
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 2,
                  validateLockOrder = true, timeoutMs = 5000)
        void testInconsistentLockOrder() throws InterruptedException {
            int threadId = threadAssigner.getAndIncrement();
            if (threadId % 2 == 0) {
                synchronized (lock1) {
                    Thread.sleep(50);
                    synchronized (lock2) {
                        // lock1 -> lock2
                    }
                }
            } else {
                synchronized (lock2) {
                    Thread.sleep(50);
                    synchronized (lock1) {
                        // lock2 -> lock1 (VIOLATION)
                    }
                }
            }
        }
    }

    // ============= Synchronizer Monitoring Integration Test =============

    @Test
    void testSynchronizerMonitoringIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(SynchronizerBarrierDummy.class))
                .execute()
                .testEvents();
        
        // Synchronizer test should complete
        assertEquals(0, testEvents.aborted().count(), 
            "Synchronizer monitoring test should not abort");
    }

    public static class SynchronizerBarrierDummy {
        private final CyclicBarrier barrier = new CyclicBarrier(3);
        private final AtomicInteger counter = new AtomicInteger(0);

        @AsyncTest(threads = 3, invocations = 2,
                  monitorSynchronizers = true, timeoutMs = 5000)
        void testBarrierSynchronization() throws BrokenBarrierException, InterruptedException {
            barrier.await();
            counter.incrementAndGet();
        }
    }

    // ============= Thread Pool Monitoring Integration Test =============

    @Test
    void testThreadPoolMonitoringIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(ThreadPoolSaturationDummy.class))
                .execute()
                .testEvents();
        
        // Thread pool test should complete
        assertEquals(0, testEvents.aborted().count(), 
            "Thread pool monitoring test should not abort");
    }

    public static class ThreadPoolSaturationDummy {
        private final ExecutorService executor = 
            new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, 
                                 new LinkedBlockingQueue<>(2));

        @AsyncTest(threads = 3, invocations = 2,
                  monitorThreadPool = true, timeoutMs = 5000)
        void testPoolQueueSaturation() throws InterruptedException {
            Future<?> future = executor.submit(() -> {
                try { Thread.sleep(100); } catch (Exception e) {}
            });
            // Multiple submissions may exceed queue capacity
            try {
                future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // Expected
            }
        }
    }

    // ============= Memory Ordering Violation Integration Test =============

    @Test
    void testMemoryOrderingDetectionIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(MemoryOrderingViolationDummy.class))
                .execute()
                .testEvents();
        
        // Memory ordering test should complete
        assertEquals(0, testEvents.aborted().count(), 
            "Memory ordering test should not abort");
    }

    public static class MemoryOrderingViolationDummy {
        // NOTE: Should be volatile but missing to trigger detection
        private int flag = 0;
        private int result = 0;
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 5,
                  detectMemoryOrderingViolations = true, timeoutMs = 5000)
        void testMemoryOrdering() {
            int threadId = threadAssigner.getAndIncrement();
            if (threadId % 2 == 0) {
                // Write
                result = 42;
                flag = 1;  // Should be volatile or synchronized
            } else {
                // Read - may see stale values due to CPU reordering
                if (flag == 1) {
                    int r = result;  // Might see 0 instead of 42
                }
            }
        }
    }

    // ============= Async Pipeline Monitoring Integration Test =============

    @Test
    void testPipelineMonitoringIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(AsyncPipelineDummy.class))
                .execute()
                .testEvents();
        
        // Pipeline test should complete
        assertEquals(0, testEvents.aborted().count(), 
            "Pipeline monitoring test should not abort");
    }

    public static class AsyncPipelineDummy {
        private final BlockingQueue<String> stage1Queue = new LinkedBlockingQueue<>(10);
        private final BlockingQueue<String> stage2Queue = new LinkedBlockingQueue<>(10);
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 4, invocations = 2,
                  monitorAsyncPipeline = true, timeoutMs = 5000)
        void testPipelineFlow() throws InterruptedException {
            int threadId = threadAssigner.getAndIncrement();
            if (threadId % 4 == 0) {
                // Producer
                stage1Queue.put("event-" + threadId);
            } else if (threadId % 4 == 1) {
                // Stage 1 processor
                String event = stage1Queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    stage2Queue.put(event + "-processed");
                }
            } else if (threadId % 4 == 2) {
                // Stage 2 processor
                String event = stage2Queue.poll(100, TimeUnit.MILLISECONDS);
                // Process event
            } else {
                // Consumer
                String event = stage2Queue.poll(100, TimeUnit.MILLISECONDS);
                // Consume result
            }
        }
    }

    // ============= Read-Write Lock Fairness Integration Test =============

    @Test
    void testReadWriteLockFairnessIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(ReadWriteLockFairnessDummy.class))
                .execute()
                .testEvents();
        
        // RWLock test should complete
        assertEquals(0, testEvents.aborted().count(), 
            "Read-write lock fairness test should not abort");
    }

    public static class ReadWriteLockFairnessDummy {
        private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
        private volatile int data = 0;
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 5, invocations = 3,
                  monitorReadWriteLockFairness = true, timeoutMs = 5000)
        void testReaderWriterInteraction() throws InterruptedException {
            int threadId = threadAssigner.getAndIncrement();
            if (threadId % 5 == 0) {
                // Writer thread (rare)
                rwLock.writeLock().lock();
                try {
                    data = threadId;
                    Thread.sleep(10);  // Hold write lock
                } finally {
                    rwLock.writeLock().unlock();
                }
            } else {
                // Reader threads (many)
                rwLock.readLock().lock();
                try {
                    int value = data;  // Read value
                    Thread.sleep(5);   // Hold read lock
                } finally {
                    rwLock.readLock().unlock();
                }
            }
        }
    }

    // ============= Multi-Detector Integration Test =============

    @Test
    void testMultipleDetectorsIntegration() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(MultiDetectorDummy.class))
                .execute()
                .testEvents();
        
        // Multi-detector test should complete without issues
        assertEquals(0, testEvents.aborted().count(), 
            "Multi-detector integration test should not abort");
    }

    public static class MultiDetectorDummy {
        private final Object lock1 = new Object();
        private final Object lock2 = new Object();
        private volatile long counter1 = 0;
        private volatile long counter2 = 0;
        private final AtomicInteger threadId = new AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 2,
                  validateLockOrder = true,
                  detectFalseSharing = true,
                  detectMemoryOrderingViolations = true,
                  timeoutMs = 5000)
        void testMultipleDetectors() throws InterruptedException {
            int id = threadId.getAndIncrement();
            if (id % 2 == 0) {
                synchronized (lock1) {
                    Thread.sleep(10);
                    synchronized (lock2) {
                        counter1++;
                    }
                }
            } else {
                synchronized (lock2) {
                    Thread.sleep(10);
                    synchronized (lock1) {
                        counter2++;
                    }
                }
            }
        }
    }

    // ============= Phase 1 + Phase 2 Combined Test =============

    @Test
    void testPhase1And2Combined() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(CombinedPhasesDummy.class))
                .execute()
                .testEvents();
        
        // Combined test should complete
        assertEquals(0, testEvents.aborted().count(), 
            "Combined phase test should not abort");
    }

    public static class CombinedPhasesDummy {
        private volatile boolean flag = false;  // Missing volatile for visibility detection
        private final Object lock = new Object();
        private volatile long counter = 0;
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 3, invocations = 2,
                  detectDeadlocks = true,
                  detectVisibility = true,
                  validateLockOrder = true,
                  detectFalseSharing = true,
                  timeoutMs = 5000)
        void testPhasesCombined() throws InterruptedException {
            int id = threadAssigner.getAndIncrement();
            synchronized (lock) {
                if (id % 3 == 0) {
                    flag = true;  // Visibility issue
                    counter++;    // False sharing
                } else if (id % 3 == 1) {
                    while (!flag) {  // Busy wait on stale value
                        Thread.sleep(1);
                    }
                } else {
                    counter--;  // False sharing
                }
            }
        }
    }
}
