package com.github.asynctest.fixture;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.AsyncTestContext;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConsumerAsyncTestUsageTest {

    // Phase 1: Core Detectors - shared state
    private int unsafeCounter = 0;
    private volatile boolean volatileFlag = false;
    private final Object lock1 = new Object();
    private final Object lock2 = new Object();
    private final AtomicReference<String> abaValue = new AtomicReference<>("A");
    private final AtomicBoolean livelockDone = new AtomicBoolean(false);

    // Phase 2: Advanced Detectors - shared state
    private volatile long falseShareA = 0;
    private volatile long falseShareB = 0;
    private final Object monitor = new Object();
    private boolean ready = false;
    private final AtomicReference<Service> serviceRef = new AtomicReference<>();
    private final AtomicReference<String> pipelineValue = new AtomicReference<>();
    private final BlockingQueue<String> asyncQueue = new ArrayBlockingQueue<>(1);
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    // Phase 3: Runtime Misuse Detectors - shared state
    private final Map<String, Integer> unsafeMap = new HashMap<>();
    private static final ThreadLocal<String> REQUEST_CTX = new ThreadLocal<>();
    private final AtomicBoolean busyWaitDone = new AtomicBoolean(false);
    private final Map<String, String> cache = new HashMap<>();

    // ============================================
    // PHASE 1: Core Detectors
    // ============================================

    /**
     * Phase 1.1: Basic race condition detection.
     * Multiple threads increment an unsynchronized counter without atomicity.
     */
    @AsyncTest(threads = 10, invocations = 50)
    void testRaceCondition() {
        unsafeCounter++;
    }

    /**
     * Phase 1.2: Deadlock detection with circular lock dependencies.
     * Even threads acquire lock1 then lock2; odd threads acquire lock2 then lock1.
     */
    @AsyncTest(threads = 2, timeoutMs = 3000, detectDeadlocks = true)
    void testDeadlock() throws InterruptedException {
        boolean even = Thread.currentThread().getId() % 2 == 0;
        Object first = even ? lock1 : lock2;
        Object second = even ? lock2 : lock1;
        
        synchronized (first) {
            Thread.sleep(5);
            synchronized (second) {
                // work
            }
        }
    }

    /**
     * Phase 1.3: Visibility issue detection.
     * Non-volatile field updated across threads and invocations.
     */
    @AsyncTest(threads = 8, invocations = 50, detectVisibility = true)
    void testVisibilityIssue() {
        volatileFlag = !volatileFlag;
    }

    /**
     * Phase 1.4: Livelock detection with CAS spinning.
     * Threads perform compare-and-set in a loop without making progress.
     */
    @AsyncTest(threads = 4, detectLivelocks = true, timeoutMs = 5000)
    void testLivelock() {
        while (!livelockDone.compareAndSet(false, true)) {
            livelockDone.set(false);
            Thread.yield();
        }
        livelockDone.set(false);
    }

    /**
     * Phase 1.5: Virtual thread stress testing.
     * Tests with many virtual threads to detect pinning issues.
     */
    @AsyncTest(useVirtualThreads = true, virtualThreadStressMode = "LOW", detectFalseSharing = true)
    void testVirtualThreadStress() {
        // Work that exercises virtual thread scheduling
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += i;
        }
        assertNotNull(AsyncTestContext.get());
    }

    // ============================================
    // PHASE 2: Advanced Detectors
    // ============================================

    /**
     * Phase 2.1: False sharing detection.
     * Two volatile fields accessed by different threads on same cache line.
     */
    @AsyncTest(threads = 4, detectFalseSharing = true)
    void testFalseSharing() {
        falseShareA++;
        falseShareB++;
        AsyncTestContext.falseSharingDetector()
            .recordFieldAccess(this, "falseShareA", long.class);
    }

    /**
     * Phase 2.2: Wakeup issues - spurious wakeup and lost notifications.
     */
    @AsyncTest(threads = 4, detectWakeupIssues = true, timeoutMs = 5000)
    void testWakeupIssues() throws InterruptedException {
        synchronized (monitor) {
            monitor.wait(10);
            ready = true;
            monitor.notify();
        }
    }

    /**
     * Phase 2.3: Constructor safety - object published before fully constructed.
     */
    @AsyncTest(threads = 4, validateConstructorSafety = true)
    void testConstructorSafety() {
        new Service(serviceRef);
    }

    /**
     * Phase 2.4: ABA problem detection in lock-free code.
     */
    @AsyncTest(threads = 4, detectABAProblem = true)
    void testABAProblem() {
        String snapshot = abaValue.get();
        abaValue.compareAndSet(snapshot, "C");
    }

    /**
     * Phase 2.5: Lock ordering validation.
     * Different threads acquire locks in different orders.
     */
    @AsyncTest(threads = 2, validateLockOrder = true, timeoutMs = 3000)
    void testLockOrdering() throws InterruptedException {
        boolean even = Thread.currentThread().getId() % 2 == 0;
        Object first = even ? lock1 : lock2;
        Object second = even ? lock2 : lock1;
        
        synchronized (first) {
            Thread.sleep(1);
            synchronized (second) {
                // work
            }
        }
    }

    /**
     * Phase 2.6: Synchronizer monitoring - CyclicBarrier advancement.
     */
    @AsyncTest(threads = 3, monitorSynchronizers = true, timeoutMs = 3000)
    void testSynchronizerMisuse() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(3);
        // Random early exit to trigger barrier stall
        if (Thread.currentThread().getId() % 7 != 0) {
            barrier.await();
        }
    }

    /**
     * Phase 2.7: Thread pool monitoring - executor saturation and deadlock.
     */
    @AsyncTest(threads = 2, monitorThreadPool = true, timeoutMs = 5000)
    void testThreadPoolDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<?> future = pool.submit(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            future.get();
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Phase 2.8: Memory ordering violation detection.
     */
    @AsyncTest(threads = 2, detectMemoryOrderingViolations = true, timeoutMs = 3000)
    void testMemoryOrderingViolation() {
        int data = 42;
        volatileFlag = true;
    }

    /**
     * Phase 2.9: Async pipeline signal loss monitoring.
     */
    @AsyncTest(threads = 2, monitorAsyncPipeline = true, timeoutMs = 3000)
    void testAsyncPipelineSignalLoss() throws Exception {
        asyncQueue.offer("event");
        String e = asyncQueue.poll();
        if (e != null) {
            pipelineValue.set(e);
        }
    }

    /**
     * Phase 2.10: Read-write lock fairness monitoring.
     */
    @AsyncTest(threads = 5, monitorReadWriteLockFairness = true, timeoutMs = 3000)
    void testReadWriteLockFairness() {
        rwLock.readLock().lock();
        try {
            // Simulate read work
            Thread.yield();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ============================================
    // PHASE 3: Runtime Misuse Detectors
    // ============================================

    /**
     * Phase 3.1: Race condition detection (Phase 3 monitor).
     */
    @AsyncTest(threads = 8, detectRaceConditions = true)
    void testRaceConditionDetection() {
        int current = unsafeMap.getOrDefault("count", 0);
        unsafeMap.put("count", current + 1);
    }

    /**
     * Phase 3.2: ThreadLocal leak detection.
     */
    @AsyncTest(threads = 5, detectThreadLocalLeaks = true)
    void testThreadLocalLeak() {
        REQUEST_CTX.set(UUID.randomUUID().toString());
        // Intentional: not calling REQUEST_CTX.remove()
    }

    /**
     * Phase 3.3: Busy-wait detection (spin loops).
     */
    @AsyncTest(threads = 2, detectBusyWaiting = true, timeoutMs = 3000)
    void testBusyWaiting() {
        if (Thread.currentThread().getId() % 2 == 0) {
            busyWaitDone.set(true);
        } else {
            while (!busyWaitDone.get()) {
                // Tight spin
                Thread.yield();
            }
        }
    }

    /**
     * Phase 3.4: Atomicity violation detection.
     */
    @AsyncTest(threads = 8, detectAtomicityViolations = true)
    void testAtomicityViolation() {
        if (!cache.containsKey("result")) {
            cache.put("result", "computed");
        }
    }

    /**
     * Phase 3.5: Interrupt mishandling monitoring.
     */
    @AsyncTest(threads = 4, detectInterruptMishandling = true)
    void testInterruptMishandling() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // Intentional: not restoring interrupt status
        }
    }

    // ============================================
    // LEGACY: Manual Validator Tests
    // ============================================

    /**
     * Legacy 1: Notify vs NotifyAll (manual validator pattern).
     * Note: This is a demonstration of manual validator usage for legacy code.
     */
    @AsyncTest(threads = 2, timeoutMs = 3000)
    void testNotifyVsNotifyAll() throws InterruptedException {
        // This demonstrates the pattern; actual validation would use NotifyAllValidator
        Object localMonitor = new Object();
        synchronized (localMonitor) {
            localMonitor.wait(10);
            localMonitor.notify();
        }
    }

    /**
     * Legacy 2: Lazy initialization (double-checked locking without volatile).
     */
    @AsyncTest(threads = 4, timeoutMs = 3000)
    void testLazyInitialization() {
        // Demonstrates unsafe DCL pattern
        if (serviceRef.get() == null) {
            serviceRef.set(new Service(null));
        }
    }

    /**
     * Legacy 3: Future blocking on bounded executor.
     */
    @AsyncTest(threads = 2, timeoutMs = 3000)
    void testFutureBlocking() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            var future = pool.submit(() -> {
                try {
                    Thread.sleep(20);
                    return "result";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            });
            future.get();
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Legacy 4: Executor self-deadlock (task waits on sibling on single-thread executor).
     */
    @AsyncTest(threads = 1, timeoutMs = 3000)
    void testExecutorSelfDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            var innerFuture = pool.submit(() -> "inner");
            var outerFuture = pool.submit(() -> {
                try {
                    return innerFuture.get();
                } catch (Exception e) {
                    return "error";
                }
            });
            outerFuture.get();
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Legacy 5: Latch misuse (countDown more times than latch count).
     */
    @AsyncTest(threads = 2, timeoutMs = 3000)
    void testLatchMisuse() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();
        latch.await();
    }

    // Helper class for constructor safety tests
    private static class Service {
        private final AtomicReference<Service> ref;

        Service(AtomicReference<Service> ref) {
            this.ref = ref;
            if (ref != null) {
                ref.set(this);
            }
        }
    }
}
