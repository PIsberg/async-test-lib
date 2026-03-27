package com.github.asynctest.fixture;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.AsyncTestContext;
import com.github.asynctest.DetectorType;
import com.github.asynctest.diagnostics.NotifyAllValidator;
import com.github.asynctest.diagnostics.LazyInitValidator;
import com.github.asynctest.diagnostics.FutureBlockingDetector;
import com.github.asynctest.diagnostics.ExecutorDeadlockDetector;
import com.github.asynctest.diagnostics.LatchMisuseDetector;
import com.github.asynctest.diagnostics.SemaphoreMisuseDetector;
import com.github.asynctest.diagnostics.CompletableFutureExceptionDetector;
import com.github.asynctest.diagnostics.ConcurrentModificationDetector;
import com.github.asynctest.diagnostics.LockLeakDetector;
import com.github.asynctest.diagnostics.SharedRandomDetector;
import com.github.asynctest.diagnostics.BlockingQueueDetector;
import com.github.asynctest.diagnostics.ConditionVariableDetector;
import com.github.asynctest.diagnostics.SimpleDateFormatDetector;
import com.github.asynctest.diagnostics.ParallelStreamDetector;
import com.github.asynctest.diagnostics.ResourceLeakDetector;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
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
    private final AtomicReference<String> abaValue = new AtomicReference<>("A");
    private final AtomicBoolean livelockTurn = new AtomicBoolean(false);
    private int data = 0;
    private boolean ready = false;

    // Phase 2: Advanced Detectors - shared state
    private volatile long falseShareA = 0;
    private volatile long falseShareB = 0;
    private final Object monitor = new Object();
    private boolean monitorReady = false;
    private final AtomicReference<Service> serviceRef = new AtomicReference<>();
    private final AtomicReference<String> pipelineValue = new AtomicReference<>();
    private final BlockingQueue<String> asyncQueue = new ArrayBlockingQueue<>(1);
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    // Phase 3: Runtime Misuse Detectors - shared state
    private final Map<String, Integer> unsafeMap = new HashMap<>();
    private static final ThreadLocal<String> REQUEST_CTX = new ThreadLocal<>();
    private final AtomicBoolean busyWaitDone = new AtomicBoolean(false);
    private final Map<String, String> cache = new HashMap<>();

    // Legacy: Manual Validator Tests - shared state
    private final NotifyAllValidator notifyAllValidator = new NotifyAllValidator();
    private final LazyInitValidator lazyInitValidator = new LazyInitValidator();
    private final FutureBlockingDetector futureBlockingDetector = new FutureBlockingDetector();
    private final ExecutorDeadlockDetector executorDeadlockDetector = new ExecutorDeadlockDetector();
    private final LatchMisuseDetector latchMisuseDetector = new LatchMisuseDetector();
    private final SemaphoreMisuseDetector semaphoreMisuseDetector = new SemaphoreMisuseDetector();
    private final CompletableFutureExceptionDetector completableFutureExceptionDetector = new CompletableFutureExceptionDetector();
    private final ConcurrentModificationDetector concurrentModificationDetector = new ConcurrentModificationDetector();
    private final LockLeakDetector lockLeakDetector = new LockLeakDetector();
    private final SharedRandomDetector sharedRandomDetector = new SharedRandomDetector();
    private final BlockingQueueDetector blockingQueueDetector = new BlockingQueueDetector();
    private final ConditionVariableDetector conditionVariableDetector = new ConditionVariableDetector();
    private final SimpleDateFormatDetector simpleDateFormatDetector = new SimpleDateFormatDetector();
    private final ParallelStreamDetector parallelStreamDetector = new ParallelStreamDetector();
    private final ResourceLeakDetector resourceLeakDetector = new ResourceLeakDetector();

    // ============================================
    // PHASE 1: Core Detectors
    // ============================================

    /**
     * Phase 1.1: Basic race condition detection with benchmarking.
     * Multiple threads increment an unsynchronized counter without atomicity.
     */
    @AsyncTest(threads = 10, invocations = 50, detectAll = true)
    void testRaceCondition() {
        unsafeCounter++;
    }

    /**
     * Phase 1.3: Visibility issue detection with benchmarking.
     * Non-volatile field updated across threads and invocations.
     */
    @AsyncTest(threads = 8, invocations = 50, detectAll = true)
    void testVisibilityIssue() {
        volatileFlag = !volatileFlag;
    }

    /**
     * Phase 1.5: Virtual thread stress testing.
     * Tests with many virtual threads to detect pinning issues.
     */
    @AsyncTest(useVirtualThreads = true, virtualThreadStressMode = "LOW", detectAll = true)
    void testVirtualThreadStress() {
        // Work that exercises virtual thread scheduling
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += i;
        }
        assertNotNull(AsyncTestContext.get());
    }

    /**
     * Phase 1.2: Livelock detection.
     * Threads keep changing state without making progress.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testLivelock() {
        // Threads keep backing off when they collide — CPU churns but no work completes
        while (!livelockTurn.compareAndSet(false, true)) {
            livelockTurn.set(false);  // back off and retry immediately
        }
        livelockTurn.set(false);  // release
    }

    /**
     * Phase 1.4: Memory model validation - JMM happens-before violations.
     */
    @AsyncTest(threads = 2, detectAll = true, timeoutMs = 3000)
    void testMemoryModelValidation() {
        // Non-volatile write may be reordered or not visible
        data = 42;
        ready = true;  // missing volatile — reader may see ready=true but data=0
    }

    // ============================================
    // PHASE 2: Advanced Detectors
    // ============================================

    /**
     * Phase 2.1: False sharing detection with benchmarking.
     * Two volatile fields accessed by different threads on same cache line.
     */
    @AsyncTest(threads = 4, detectAll = true)
    void testFalseSharing() {
        falseShareA++;
        falseShareB++;
        AsyncTestContext.falseSharingDetector()
            .recordFieldAccess(this, "falseShareA", long.class);
    }

    /**
     * Phase 2.2: Wakeup issues - spurious wakeup and lost notifications.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 5000)
    void testWakeupIssues() throws InterruptedException {
        synchronized (monitor) {
            monitor.wait(10);
            monitorReady = true;
            monitor.notify();
        }
    }

    /**
     * Phase 2.5: Lock ordering violation detection with benchmarking.
     * Different threads acquire locks in different orders — classic deadlock setup.
     */
    @AsyncTest(threads = 2, detectAll = true, timeoutMs = 3000)
    void testLockOrderingViolation() throws InterruptedException {
        Object lockA = new Object();
        Object lockB = new Object();
        // Even threads: lockA→lockB; odd threads: lockB→lockA — inconsistent ordering
        boolean even = Thread.currentThread().getId() % 2 == 0;
        synchronized (even ? lockA : lockB) {
            Thread.sleep(5);
            synchronized (even ? lockB : lockA) {
                // work
            }
        }
    }

    /**
     * Phase 2.3: Constructor safety - object published before fully constructed.
     */
    @AsyncTest(threads = 4, detectAll = true)
    void testConstructorSafety() {
        new Service(serviceRef);
    }

    /**
     * Phase 2.4: ABA problem detection in lock-free code.
     */
    @AsyncTest(threads = 4, detectAll = true)
    void testABAProblem() {
        String snapshot = abaValue.get();
        abaValue.compareAndSet(snapshot, "C");
    }

    /**
     * Phase 2.6: Synchronizer monitoring - all threads correctly participate in a barrier.
     */
    @AsyncTest(threads = 3, detectAll = true, timeoutMs = 3000)
    void testSynchronizerMonitor() throws Exception {
        // All threads participate — correct use triggers the monitor
    }

    /**
     * Phase 2.7: Thread pool monitoring - executor saturation and deadlock.
     */
    @AsyncTest(threads = 2, invocations = 5, detectAll = true, timeoutMs = 5000)
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
    @AsyncTest(threads = 2, detectAll = true, timeoutMs = 3000)
    void testMemoryOrderingViolation() {
        int data = 42;
        volatileFlag = true;
    }

    /**
     * Phase 2.9: Async pipeline signal loss monitoring.
     */
    @AsyncTest(threads = 2, detectAll = true, timeoutMs = 3000)
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
    @AsyncTest(threads = 5, detectAll = true, timeoutMs = 3000)
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
    @AsyncTest(threads = 8, detectAll = true)
    void testRaceConditionDetection() {
        int current = unsafeMap.getOrDefault("count", 0);
        unsafeMap.put("count", current + 1);
    }

    /**
     * Phase 3.2: ThreadLocal leak detection.
     */
    @AsyncTest(threads = 5, detectAll = true)
    void testThreadLocalLeak() {
        REQUEST_CTX.set(UUID.randomUUID().toString());
        // Intentional: not calling REQUEST_CTX.remove()
    }

    /**
     * Phase 3.3: Busy-wait detection (spin loops).
     */
    @AsyncTest(threads = 2, detectAll = true, timeoutMs = 3000)
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
    @AsyncTest(threads = 8, detectAll = true)
    void testAtomicityViolation() {
        if (!cache.containsKey("result")) {
            cache.put("result", "computed");
        }
    }

    /**
     * Phase 3.5: Interrupt mishandling monitoring.
     */
    @AsyncTest(threads = 4, detectAll = true)
    void testInterruptMishandling() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // Intentional: not restoring interrupt status
        }
    }

    // ============================================
    // LEGACY: Manual Validator Tests (21-25)
    // ============================================

    /**
     * Legacy 21: Notify vs NotifyAll — using notify() with multiple waiters.
     * When multiple threads wait on a monitor, notify() wakes only one, leaving others stranded.
     */
    @AsyncTest(threads = 3, timeoutMs = 3000)
    void testNotifyVsNotifyAll() throws InterruptedException {
        Object localMonitor = new Object();
        
        // Simulate multiple waiters (in real code this would be coordinated across threads)
        notifyAllValidator.recordWaiterAdded(localMonitor, "queue");
        notifyAllValidator.recordWaiterAdded(localMonitor, "queue");
        
        synchronized (localMonitor) {
            localMonitor.wait(10);
            // Bug: notify() instead of notifyAll() — one waiter left sleeping
            notifyAllValidator.recordNotify(localMonitor, false);
            localMonitor.notify();
        }
        
        // Analyze and report (for demonstration, we just print the report)
        var report = notifyAllValidator.analyze();
        // In real usage, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Legacy 22: Lazy Initialization — double-checked locking without volatile.
     * Two threads may see null simultaneously and both attempt initialization.
     */
    @AsyncTest(threads = 4, timeoutMs = 3000)
    void testLazyInitialization() {
        // Simulate concurrent access to singleton
        lazyInitValidator.recordAccess("Config", true, true, false, false);
        lazyInitValidator.recordAccess("Config", true, true, false, false);
        
        // Actual unsafe DCL pattern
        if (serviceRef.get() == null) {
            serviceRef.set(new Service(null));
        }
        
        // Analyze and report (for demonstration, we just print the report)
        var report = lazyInitValidator.analyze();
        // In real usage, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Legacy 23: Future Blocking — calling get() inside bounded pool starves executor.
     * When tasks block waiting for other tasks in the same pool, starvation occurs.
     */
    @AsyncTest(threads = 2, invocations = 3, timeoutMs = 3000)
    void testFutureBlocking() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        futureBlockingDetector.registerExecutor(pool, "boundedPool", 2);
        futureBlockingDetector.recordTaskStarted(pool);
        
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
            // Bug: blocking get() inside same pool — may starve if pool is saturated
            futureBlockingDetector.recordBlockingWait(pool);
            future.get();
        } finally {
            pool.shutdown();
        }
        
        // Analyze and report (for demonstration, we just print the report)
        var report = futureBlockingDetector.analyze();
        // In real usage, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Legacy 24: Executor Self-Deadlock — task waits on sibling in same single-thread executor.
     * Submitting a task and waiting for it inside another task deadlocks single-thread pools.
     */
    @AsyncTest(threads = 1, timeoutMs = 3000)
    void testExecutorSelfDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        executorDeadlockDetector.registerExecutor(pool, "singleThread", 1);
        executorDeadlockDetector.recordTaskStarted(pool);
        
        try {
            var innerFuture = pool.submit(() -> "inner");
            executorDeadlockDetector.recordTaskSubmitted(pool);
            
            var outerFuture = pool.submit(() -> {
                try {
                    executorDeadlockDetector.recordWaitingOnSibling(pool);
                    return innerFuture.get();
                } catch (Exception e) {
                    return "error";
                }
            });
            outerFuture.get();
        } finally {
            pool.shutdown();
        }
        
        // Analyze and report (for demonstration, we just print the report)
        var report = executorDeadlockDetector.analyze();
        // In real usage, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Legacy 25: Latch Misuse — countDown() called more times than latch count.
     * Extra countDown() calls or missing await() can cause synchronization failures.
     */
    @AsyncTest(threads = 2, timeoutMs = 3000)
    void testLatchMisuse() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        latchMisuseDetector.registerLatch(latch, "startupGate", 1);
        
        // First thread counts down correctly
        latchMisuseDetector.recordCountDown(latch);
        latch.countDown();
        
        // Second thread also counts down — bug: more countDown() than initial count
        latchMisuseDetector.recordCountDown(latch);
        latch.countDown();
        
        latch.await();
        
        // Analyze and report (for demonstration, we just print the report)
        var report = latchMisuseDetector.analyze();
        // In real usage, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.11: Semaphore misuse — permit leak detection.
     * When acquire() is not matched with release(), permits are leaked.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testSemaphorePermitLeak() throws Exception {
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(2);
        semaphoreMisuseDetector.registerSemaphore(semaphore, "resource-pool", 2);
        
        try {
            semaphore.acquire();
            semaphoreMisuseDetector.recordAcquire(semaphore, "resource-pool");
            // Simulate work with the resource
            Thread.sleep(1);
        } finally {
            semaphore.release();
            semaphoreMisuseDetector.recordRelease(semaphore, "resource-pool");
        }
        
        // Analyze and report (for demonstration, we just print the report)
        var report = semaphoreMisuseDetector.analyze();
        // In real usage with a leak, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.12: CompletableFuture exception handling — unhandled exceptions in async chains.
     * When a CompletableFuture completes exceptionally without a handler, the exception is lost.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testCompletableFutureExceptionHandling() {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        completableFutureExceptionDetector.recordFutureCreated(future, "async-task");
        
        // Register exception handler
        future.exceptionally(ex -> {
            completableFutureExceptionDetector.recordExceptionHandled(future, "async-task", ex);
            return "default";
        });
        
        // Complete with exception
        future.completeExceptionally(new RuntimeException("async error"));
        completableFutureExceptionDetector.recordFutureCompleted(future, "async-task", false);
        
        // Analyze and report (for demonstration, we just print the report)
        var report = completableFutureExceptionDetector.analyze();
        // In real usage with unhandled exception, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.13: Concurrent modification detection — safe collection iteration.
     * Using CopyOnWriteArrayList to avoid ConcurrentModificationException during iteration.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testConcurrentModificationSafe() {
        java.util.List<String> list = new java.util.concurrent.CopyOnWriteArrayList<>();
        concurrentModificationDetector.registerCollection(list, "safe-list");
        
        // Safe iteration
        concurrentModificationDetector.recordIterationStarted(list, "safe-list");
        for (String item : list) {
            // read-only access
        }
        concurrentModificationDetector.recordIterationEnded(list, "safe-list");
        
        // Safe modification (outside iteration)
        list.add("item-" + Thread.currentThread().getId());
        concurrentModificationDetector.recordModification(list, "safe-list", "add");
        
        // Analyze and report (for demonstration, we just print the report)
        var report = concurrentModificationDetector.analyze();
        // In real usage with concurrent modifications, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.14: Lock leak detection — proper lock usage with try-finally.
     * Using try-finally ensures lock is always released even if exception occurs.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testLockLeakProperUsage() {
        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        lockLeakDetector.registerLock(lock, "proper-lock");
        
        lock.lock();
        lockLeakDetector.recordLockAcquired(lock, "proper-lock");
        try {
            // critical section - simulate work
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
            lockLeakDetector.recordLockReleased(lock, "proper-lock");
        }
        
        // Analyze and report (for demonstration, we just print the report)
        var report = lockLeakDetector.analyze();
        // In real usage with a leak, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.15: Shared Random detection — detecting concurrent Random access.
     * Using ThreadLocalRandom instead of shared Random for thread-safe random generation.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testSharedRandomDetection() {
        java.util.Random random = new java.util.Random();
        sharedRandomDetector.registerRandom(random, "shared-random");
        
        // This will be detected as shared access (not recommended)
        int value = random.nextInt();
        sharedRandomDetector.recordRandomAccess(random, "shared-random", "nextInt");
        
        // Better approach: use ThreadLocalRandom
        int betterValue = java.util.concurrent.ThreadLocalRandom.current().nextInt();
        
        // Analyze and report (for demonstration, we just print the report)
        var report = sharedRandomDetector.analyze();
        // In real usage with shared random, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.16: BlockingQueue misuse detection — silent failures and saturation.
     * Using offer() without checking return value can silently drop items.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testBlockingQueueUsage() throws InterruptedException {
        java.util.concurrent.BlockingQueue<String> queue = new java.util.concurrent.ArrayBlockingQueue<>(10);
        blockingQueueDetector.registerQueue(queue, "work-queue", 10);
        
        // Producer - check return value!
        boolean added = queue.offer("item-" + Thread.currentThread().getId());
        blockingQueueDetector.recordOffer(queue, "work-queue", added);
        
        // Consumer - check for null!
        String item = queue.poll();
        blockingQueueDetector.recordPoll(queue, "work-queue", item != null);
        
        // Analyze and report (for demonstration, we just print the report)
        var report = blockingQueueDetector.analyze();
        // In real usage with queue issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.17: Condition variable misuse detection — lost signals and stuck waiters.
     * Using Condition with ReentrantLock for thread coordination.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testConditionVariableUsage() throws InterruptedException {
        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.locks.Condition condition = lock.newCondition();
        conditionVariableDetector.registerCondition(condition, "data-ready");
        
        lock.lock();
        try {
            // Signal (in real code, this should follow state change)
            conditionVariableDetector.recordSignal(condition, "data-ready", false);
            condition.signal();
        } finally {
            lock.unlock();
        }
        
        // Analyze and report (for demonstration, we just print the report)
        var report = conditionVariableDetector.analyze();
        // In real usage with condition issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.18: SimpleDateFormat misuse detection — concurrent access to non-thread-safe formatter.
     * SimpleDateFormat is NOT thread-safe; use DateTimeFormatter (Java 8+) or ThreadLocal instead.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testSimpleDateFormatUsage() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        simpleDateFormatDetector.registerFormatter(sdf, "date-formatter");
        
        // Bug: SimpleDateFormat is not thread-safe!
        String formatted = sdf.format(new java.util.Date());
        simpleDateFormatDetector.recordFormat(sdf, "date-formatter");
        
        // Fix: use DateTimeFormatter or ThreadLocal<SimpleDateFormat>
        // String safe = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(java.time.LocalDate.now());
        
        // Analyze and report (for demonstration, we just print the report)
        var report = simpleDateFormatDetector.analyze();
        // In real usage with shared formatter, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.19: Parallel stream misuse detection — stateful lambdas and side effects.
     * Parallel streams require stateless, non-interfering operations.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testParallelStreamUsage() {
        java.util.List<Integer> list = java.util.Arrays.asList(1, 2, 3, 4, 5);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        
        parallelStreamDetector.recordParallelStream("test-stream");
        
        // Bug: stateful lambda modifying external state in parallel stream
        list.parallelStream().forEach(i -> counter.incrementAndGet());
        parallelStreamDetector.recordStatefulOperation("test-stream", "forEach");
        
        // Fix: use stateless operations or synchronized counters
        // int sum = list.parallelStream().mapToInt(i -> i).sum();
        
        // Analyze and report (for demonstration, we just print the report)
        var report = parallelStreamDetector.analyze();
        // In real usage with stateful operations, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.20: Resource leak detection — AutoCloseable resources not properly closed.
     * Always use try-with-resources or close in finally block.
     */
    // Example of using 'excludes' to skip a detector in a specific test
    @AsyncTest(threads = 4, detectAll = true, excludes = {com.github.asynctest.DetectorType.RESOURCE_LEAKS}, timeoutMs = 3000)
    void testResourceLeakProperUsage() throws Exception {
        java.io.StringReader reader = new java.io.StringReader("test data");
        resourceLeakDetector.registerResource(reader, "proper-resource", "StringReader");
        
        try {
            reader.read();
            resourceLeakDetector.recordResourceOpened(reader, "proper-resource");
        } finally {
            reader.close();
            resourceLeakDetector.recordResourceClosed(reader, "proper-resource");
        }
        
        // Analyze and report (for demonstration, we just print the report)
        var report = resourceLeakDetector.analyze();
        // In real usage with a leak, you would assert: assertTrue(report.hasIssues())
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
