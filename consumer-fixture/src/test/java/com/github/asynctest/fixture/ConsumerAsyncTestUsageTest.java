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
import com.github.asynctest.diagnostics.CompletableFutureCompletionLeakDetector;
import com.github.asynctest.diagnostics.ThreadPoolDeadlockDetector;
import com.github.asynctest.diagnostics.ConcurrentModificationDetector;
import com.github.asynctest.diagnostics.LockLeakDetector;
import com.github.asynctest.diagnostics.SharedRandomDetector;
import com.github.asynctest.diagnostics.BlockingQueueDetector;
import com.github.asynctest.diagnostics.ConditionVariableDetector;
import com.github.asynctest.diagnostics.SimpleDateFormatDetector;
import com.github.asynctest.diagnostics.ParallelStreamDetector;
import com.github.asynctest.diagnostics.ResourceLeakDetector;
import com.github.asynctest.diagnostics.ThreadLeakDetector;
import com.github.asynctest.diagnostics.SleepInLockDetector;
import com.github.asynctest.diagnostics.UnboundedQueueDetector;
import com.github.asynctest.diagnostics.ThreadStarvationDetector;
import com.github.asynctest.diagnostics.CalendarDetector;
import com.github.asynctest.diagnostics.SharedCollectionDetector;
import com.github.asynctest.diagnostics.TimerDetector;
import com.github.asynctest.diagnostics.CopyOnWriteCollectionDetector;
import com.github.asynctest.diagnostics.StringBuilderDetector;
import com.github.asynctest.diagnostics.HttpClientConcurrencyDetector;
import com.github.asynctest.diagnostics.StreamClosingDetector;
import com.github.asynctest.diagnostics.CacheConcurrencyDetector;
import com.github.asynctest.diagnostics.CompletableFutureChainDetector;
import com.github.asynctest.diagnostics.VirtualThreadCpuBoundTaskDetector;
import com.github.asynctest.diagnostics.VirtualThreadCarrierExhaustionDetector;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.io.Closeable;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    // Synchronizers for coordination tests (must be shared across threads)
    private final java.util.concurrent.CyclicBarrier barrier3 = new java.util.concurrent.CyclicBarrier(3);
    private final java.util.concurrent.Phaser phaser3 = new java.util.concurrent.Phaser(3);
    private final java.util.concurrent.Exchanger<String> exchanger = new java.util.concurrent.Exchanger<>();
    private final Object waitLock = new Object();

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

    /**
     * Phase 2.21: CountDownLatch misuse detection — timeout and missing countDown.
     * Ensures all threads call countDown() before await() timeout.
     */
    @AsyncTest(threads = 3, detectAll = true, timeoutMs = 3000)
    void testCountDownLatchUsage() throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);
        AsyncTestContext.countDownLatchMonitor()
            .registerLatch(latch, "startupLatch", 2);

        // First thread counts down
        AsyncTestContext.countDownLatchMonitor().recordCountDown(latch);
        latch.countDown();

        // Second thread counts down
        AsyncTestContext.countDownLatchMonitor().recordCountDown(latch);
        latch.countDown();

        // Third thread waits (should succeed since count reaches 0)
        AsyncTestContext.countDownLatchMonitor().recordAwaitSuccess(latch);
        latch.await();

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.countDownLatchMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.22: CyclicBarrier misuse detection — timeout and broken barriers.
     * Ensures all threads reach the barrier before timeout.
     */
    @AsyncTest(threads = 3, detectAll = true, timeoutMs = 3000)
    void testCyclicBarrierUsage() throws Exception {
        AsyncTestContext.cyclicBarrierMonitor()
            .registerBarrier(barrier3, "phaseBarrier", 3);

        // Record arrival at barrier
        AsyncTestContext.cyclicBarrierMonitor().recordArrival(barrier3);
        barrier3.await();

        // Record successful barrier completion
        AsyncTestContext.cyclicBarrierMonitor().recordBarrierComplete(barrier3);

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.cyclicBarrierMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.23: ReentrantLock issue detection — starvation and timeouts.
     * Detects lock starvation, unfair acquisition, and lock timeouts.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testReentrantLockUsage() throws Exception {
        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        AsyncTestContext.reentrantLockMonitor()
            .registerLock(lock, "dataLock");

        lock.lock();
        AsyncTestContext.reentrantLockMonitor()
            .recordLockAcquired(lock, "dataLock");
        try {
            // critical section
            Thread.sleep(1);
        } finally {
            lock.unlock();
            AsyncTestContext.reentrantLockMonitor()
                .recordLockReleased(lock, "dataLock");
        }

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.reentrantLockMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.24: Volatile array issue detection — elements are not volatile.
     * Detects multi-thread access to volatile array elements.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testVolatileArrayUsage() {
        int[] array = new int[10];  // Note: even if field is volatile, elements are NOT
        AsyncTestContext.volatileArrayMonitor()
            .registerArray(array, "sharedArray", int.class);

        // Bug: volatile only applies to array reference, not elements
        int index = (int) (Thread.currentThread().getId() % 10);
        AsyncTestContext.volatileArrayMonitor()
            .recordElementWrite(array, index, "sharedArray");
        array[index] = 42;

        // Fix: use AtomicIntegerArray instead
        // AtomicIntegerArray atomicArray = new AtomicIntegerArray(10);

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.volatileArrayMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.25: Double-checked locking detection — broken DCL without volatile.
     * Detects DCL patterns that need volatile keyword.
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testDoubleCheckedLocking() {
        // Simulate broken DCL pattern detection
        AsyncTestContext.doubleCheckedLockingMonitor().registerDCL(
            "singletonInstance",
            false,  // isVolatile = false (bug!)
            true,   // hasFirstCheck
            true,   // hasSecondCheck
            true    // insideSynchronized
        );

        // Actual broken DCL pattern (for demonstration)
        if (serviceRef.get() == null) {
            synchronized (this) {
                if (serviceRef.get() == null) {
                    serviceRef.set(new Service(null));
                }
            }
        }

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.doubleCheckedLockingMonitor().analyze();
        // In real usage, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.26: Wait timeout detection — wait() without timeout.
     * Detects wait() calls that could block indefinitely.
     */
    @AsyncTest(threads = 3, invocations = 1, detectAll = true, timeoutMs = 3000)
    void testWaitTimeout() throws Exception {
        // Use wait with timeout (safe pattern) - short timeout to avoid test timeout
        synchronized (waitLock) {
            AsyncTestContext.waitTimeoutMonitor()
                .recordTimedWait(waitLock, "monitorLock", Thread.currentThread().getName(), 10);
            waitLock.wait(10);  // 10ms timeout - very short to avoid test timeout
        }

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.waitTimeoutMonitor().analyze();
        // In real usage with infinite waits, you would assert: assertTrue(report.hasIssues())
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

    /**
     * Phase 2.27: Phaser misuse detection — missing arrive() and timeouts.
     * Ensures all threads call arrive() before phaser advances.
     */
    @AsyncTest(threads = 3, detectAll = true, timeoutMs = 3000)
    void testPhaserUsage() throws Exception {
        AsyncTestContext.phaserMonitor()
            .registerPhaser(phaser3, "phasePhaser", 3);

        // Record arrival at phaser
        AsyncTestContext.phaserMonitor().recordArrive(phaser3);
        phaser3.arriveAndAwaitAdvance();

        // Record successful phase completion
        AsyncTestContext.phaserMonitor().recordPhaseComplete(phaser3, 1);

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.phaserMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.28: StampedLock issue detection — unvalidated optimistic reads.
     * Detects optimistic reads without calling validate().
     */
    @AsyncTest(threads = 4, detectAll = true, timeoutMs = 3000)
    void testStampedLockUsage() {
        java.util.concurrent.locks.StampedLock lock = new java.util.concurrent.locks.StampedLock();
        AsyncTestContext.stampedLockMonitor()
            .registerLock(lock, "dataLock");

        // Optimistic read
        long stamp = lock.tryOptimisticRead();
        AsyncTestContext.stampedLockMonitor()
            .recordOptimisticRead(lock, "dataLock", stamp);
        
        // Read data...
        int data = 42;
        
        // Validate optimistic read
        boolean validated = lock.validate(stamp);
        AsyncTestContext.stampedLockMonitor()
            .recordOptimisticValidation(lock, "dataLock", stamp, validated);

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.stampedLockMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.29: Exchanger misuse detection — timeout and missing partners.
     * Detects exchange timeouts when odd number of threads participate.
     */
    @AsyncTest(threads = 2, detectAll = true, timeoutMs = 3000)
    void testExchangerUsage() throws Exception {
        AsyncTestContext.exchangerMonitor()
            .registerExchanger(exchanger, "dataExchanger");

        // Record exchange start
        AsyncTestContext.exchangerMonitor()
            .recordExchangeStart(exchanger, "dataExchanger");

        // Perform exchange (2 threads will exchange with each other)
        String result = exchanger.exchange("data-" + Thread.currentThread().getId(), 1000, java.util.concurrent.TimeUnit.MILLISECONDS);

        // Record exchange complete
        AsyncTestContext.exchangerMonitor()
            .recordExchangeComplete(exchanger, "dataExchanger", result);

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.exchangerMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.30: ScheduledExecutorService issue detection — missing shutdown.
     * Detects executors not shut down after use.
     */
    @AsyncTest(threads = 2, invocations = 3, detectAll = true, timeoutMs = 3000)
    void testScheduledExecutorUsage() throws Exception {
        java.util.concurrent.ScheduledExecutorService executor = 
            java.util.concurrent.Executors.newScheduledThreadPool(2);
        AsyncTestContext.scheduledExecutorMonitor()
            .registerExecutor(executor, "scheduledPool", 2);

        try {
            // Schedule a task
            AsyncTestContext.scheduledExecutorMonitor()
                .recordSchedule(executor, "scheduledPool", "periodicTask");
            
            java.util.concurrent.Future<?> future = executor.scheduleAtFixedRate(
                () -> {
                    AsyncTestContext.scheduledExecutorMonitor()
                        .recordTaskStart(executor, "scheduledPool", "periodicTask");
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    AsyncTestContext.scheduledExecutorMonitor()
                        .recordTaskComplete(executor, "scheduledPool", "periodicTask", 1L);
                },
                0, 10, java.util.concurrent.TimeUnit.MILLISECONDS
            );
            
            Thread.sleep(50);  // Let it run briefly
            future.cancel(true);
        } finally {
            // Properly shut down executor
            AsyncTestContext.scheduledExecutorMonitor().recordShutdown(executor);
            executor.shutdown();
        }

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.scheduledExecutorMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.31: ForkJoinPool issue detection — fork without join.
     * Detects tasks that are forked but never joined.
     */
    @AsyncTest(threads = 2, detectAll = true, timeoutMs = 3000)
    void testForkJoinPoolUsage() throws Exception {
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(2);
        AsyncTestContext.forkJoinPoolMonitor()
            .registerPool(pool, "forkJoinPool", 2);

        try {
            // Create and fork a task
            java.util.concurrent.RecursiveTask<Integer> task = new java.util.concurrent.RecursiveTask<Integer>() {
                @Override
                protected Integer compute() {
                    return 42;
                }
            };
            
            AsyncTestContext.forkJoinPoolMonitor()
                .recordFork(pool, "forkJoinPool", "recursiveTask");
            task.fork();
            
            // Join the task (proper usage)
            AsyncTestContext.forkJoinPoolMonitor()
                .recordJoin(pool, "forkJoinPool", "recursiveTask");
            Integer result = task.join();
            
            AsyncTestContext.forkJoinPoolMonitor()
                .recordTaskTime(pool, "forkJoinPool", 1L);
        } finally {
            pool.shutdown();
        }

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.forkJoinPoolMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.32: ThreadFactory issue detection — missing exception handler.
     * Detects threads created without uncaught exception handler.
     */
    @AsyncTest(threads = 2, detectAll = true, timeoutMs = 3000)
    void testThreadFactoryUsage() {
        ThreadFactory factory = new ThreadFactory() {
            private int count = 0;
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "custom-thread-" + (++count));
                // Good practice: set uncaught exception handler
                t.setUncaughtExceptionHandler((thread, ex) -> 
                    System.err.println("Exception in " + thread.getName() + ": " + ex));
                return t;
            }
        };
        
        AsyncTestContext.threadFactoryMonitor()
            .registerFactory(factory, "customFactory");
        
        Thread thread = factory.newThread(() -> {
            // Thread work
        });
        
        AsyncTestContext.threadFactoryMonitor()
            .recordThreadCreated(factory, "customFactory", thread);

        // Analyze and report (for demonstration, we just print the report)
        var report = AsyncTestContext.threadFactoryMonitor().analyze();
        // In real usage with issues, you would assert: assertTrue(report.hasIssues())
    }

    /**
     * Phase 2.33: CompletableFuture completion leak detection.
     * Detects CompletableFutures created but never completed.
     * 
     * This test demonstrates proper usage: track creation and completion.
     */
    @AsyncTest(threads = 1, detectCompletableFutureCompletionLeaks = true, timeoutMs = 3000)
    void testCompletableFutureCompletionLeak() throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // Track creation
        AsyncTestContext.completableFutureCompletionLeakDetector()
            .recordFutureCreated(future, "user-lookup-future");
        
        // Complete the future synchronously in this test
        future.complete("user-data");
        
        // Track completion
        AsyncTestContext.completableFutureCompletionLeakDetector()
            .recordFutureCompleted(future, "user-lookup-future");
        
        // Analyze - should have no leaks since we completed
        var report = AsyncTestContext.completableFutureCompletionLeakDetector().analyze();
        assertFalse(report.hasLeaks(), "Completed future should not leak");
    }
    
    /**
     * Demonstrates a CompletableFuture leak - future created but never completed.
     * This test intentionally creates a leak to show detector behavior.
     */
    @AsyncTest(threads = 1, invocations = 1, detectCompletableFutureCompletionLeaks = true, timeoutMs = 3000)
    void testCompletableFutureCompletionLeak_detected() {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // Track creation
        AsyncTestContext.completableFutureCompletionLeakDetector()
            .recordFutureCreated(future, "leaked-future");
        
        // Intentionally NOT completing - simulates bug where completion is skipped
        
        // Analyze - should detect the leak
        var report = AsyncTestContext.completableFutureCompletionLeakDetector().analyze();
        assertTrue(report.hasLeaks(), "Uncompleted future should be detected as leak");
        assertEquals(1, report.getLeakCount());
    }
    
    /**
     * Phase 2.34: Thread pool deadlock detection.
     * Detects tasks submitting nested tasks to the same pool, which can cause deadlock.
     */
    @AsyncTest(threads = 1, invocations = 1, detectThreadPoolDeadlocks = true, timeoutMs = 3000)
    void testThreadPoolDeadlockDetection() {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        
        // Register pool for monitoring
        AsyncTestContext.threadPoolDeadlockDetector()
            .registerPool(pool, "test-pool");
        
        // Simulate a nested submission scenario (potential deadlock)
        AsyncTestContext.threadPoolDeadlockDetector()
            .recordNestedSubmission(pool, "test-pool");
        
        // Analyze - should detect the nested submission as a risk
        var report = AsyncTestContext.threadPoolDeadlockDetector().analyze();
        assertTrue(report.hasDeadlockRisk(), "Nested submission should be detected as deadlock risk");
        
        pool.shutdown();
    }
    
    /**
     * Demonstrates safe thread pool usage - no nested submissions.
     */
    @AsyncTest(threads = 1, invocations = 1, detectThreadPoolDeadlocks = true, timeoutMs = 3000)
    void testThreadPoolNoDeadlock() {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        
        // Register pool for monitoring
        AsyncTestContext.threadPoolDeadlockDetector()
            .registerPool(pool, "safe-pool");
        
        // No nested submissions - safe usage
        
        // Analyze - should have no risks
        var report = AsyncTestContext.threadPoolDeadlockDetector().analyze();
        assertFalse(report.hasDeadlockRisk(), "No nested submissions means no deadlock risk");
        
        pool.shutdown();
    }

    // ========================================================================
    // Phase 4: Infrastructure & Resource Management
    // ========================================================================

    /**
     * Demonstrates thread leak detection.
     * Shows how to track thread creation and termination.
     */
    @AsyncTest(threads = 2, invocations = 2, detectThreadLeaks = true, timeoutMs = 5000)
    void testThreadLeakDetection() {
        Thread backgroundThread = new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "background-worker");
        
        AsyncTestContext.threadLeakDetector()
            .recordThreadStart(backgroundThread, "background-task");
        backgroundThread.start();
        
        // Simulate work
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        
        // Properly terminate the thread
        backgroundThread.interrupt();
        AsyncTestContext.threadLeakDetector()
            .recordThreadEnd(backgroundThread);
    }

    /**
     * Demonstrates sleep-in-lock detection.
     * Shows the anti-pattern of sleeping while holding a lock.
     */
    @AsyncTest(threads = 2, invocations = 2, detectSleepInLock = true, timeoutMs = 5000)
    void testSleepInLockDetection() {
        final Object lock = new Object();
        
        // Bad pattern: sleeping while holding a lock
        // In real code, this would cause unnecessary contention
        synchronized (lock) {
            // Simulating work that mistakenly includes sleep
            // Note: Actual detection requires running test logic, this is usage example
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Demonstrates unbounded queue detection.
     * Shows the risk of using queues without capacity bounds.
     */
    @AsyncTest(threads = 2, invocations = 2, detectUnboundedQueue = true, timeoutMs = 5000)
    void testUnboundedQueueDetection() {
        // Bad: unbounded queue can grow indefinitely
        java.util.concurrent.LinkedBlockingQueue<String> unboundedQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        
        AsyncTestContext.unboundedQueueDetector()
            .recordQueueCreation(unboundedQueue, "task-queue", -1);
        
        // Good: bounded queue with rejection policy
        BlockingQueue<String> boundedQueue = new ArrayBlockingQueue<>(100);
        AsyncTestContext.unboundedQueueDetector()
            .recordQueueCreation(boundedQueue, "bounded-task-queue", 100);
    }

    /**
     * Demonstrates thread starvation detection.
     * Shows how to monitor executor thread pools for task starvation.
     */
    @AsyncTest(threads = 2, invocations = 2, detectThreadStarvation = true, timeoutMs = 10000)
    void testThreadStarvationDetection() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        AsyncTestContext.threadStarvationDetector()
            .registerExecutor(executor, "worker-pool", 2);
        
        // Submit tasks and monitor wait times
        for (int i = 0; i < 5; i++) {
            long submitTime = AsyncTestContext.threadStarvationDetector()
                .recordTaskSubmission(executor);
            
            executor.submit(() -> {
                AsyncTestContext.threadStarvationDetector()
                    .recordTaskStart("worker-pool", submitTime);
                try {
                    // Simulate work
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    AsyncTestContext.threadStarvationDetector()
                        .recordTaskEnd("worker-pool");
                }
            });
        }
        
        executor.shutdown();
    }

    // ============================================
    // PHASE 5: Thread-Safety of Common Types
    // ============================================

    /**
     * Demonstrates Calendar sharing detection.
     * java.util.Calendar is not thread-safe — multiple threads sharing one instance
     * can produce silently corrupted date values.
     */
    @AsyncTest(threads = 4, invocations = 20, detectCalendarIssues = true, timeoutMs = 5000)
    void testCalendarSharingDetection() {
        java.util.Calendar cal = java.util.Calendar.getInstance();

        AsyncTestContext.calendarMonitor()
            .registerCalendar(cal, "shared-calendar");

        // Simulate set + get cycle (data race without synchronisation)
        cal.set(java.util.Calendar.YEAR, 2024);
        AsyncTestContext.calendarMonitor()
            .recordSet(cal, "shared-calendar");

        cal.get(java.util.Calendar.YEAR);
        AsyncTestContext.calendarMonitor()
            .recordGet(cal, "shared-calendar");
    }

    /**
     * Demonstrates shared collection detection.
     * ArrayList and HashMap accessed by multiple threads without synchronisation.
     */
    @AsyncTest(threads = 4, invocations = 20, detectSharedCollections = true, timeoutMs = 5000)
    void testSharedCollectionDetection() {
        java.util.List<String> sharedList = new java.util.ArrayList<>();

        AsyncTestContext.sharedCollectionMonitor()
            .registerCollection(sharedList, "event-list", "ArrayList");

        sharedList.add("event-" + Thread.currentThread().getId());
        AsyncTestContext.sharedCollectionMonitor()
            .recordWrite(sharedList, "event-list", "add");
    }

    /**
     * Demonstrates Timer misuse detection.
     * java.util.Timer exception in one task kills the timer thread, silently
     * cancelling all remaining tasks.
     */
    @AsyncTest(threads = 2, invocations = 5, detectTimerIssues = true, timeoutMs = 10000)
    void testTimerMisuseDetection() {
        java.util.Timer timer = new java.util.Timer("demo-timer");

        AsyncTestContext.timerMonitor()
            .registerTimer(timer, "demo-timer");

        AsyncTestContext.timerMonitor()
            .recordTaskSchedule(timer, "demo-timer", "periodic-task");
        AsyncTestContext.timerMonitor()
            .recordTaskRun(timer, "demo-timer", "periodic-task");
        AsyncTestContext.timerMonitor()
            .recordTaskComplete(timer, "demo-timer", "periodic-task");

        timer.cancel();
    }

    /**
     * Demonstrates CopyOnWriteArrayList write-heavy detection.
     * High write ratio makes copy-on-write collections a performance bottleneck.
     */
    @AsyncTest(threads = 4, invocations = 20, detectCopyOnWriteCollectionIssues = true, timeoutMs = 5000)
    void testCopyOnWriteCollectionDetection() {
        java.util.concurrent.CopyOnWriteArrayList<String> cowList = new java.util.concurrent.CopyOnWriteArrayList<>();

        AsyncTestContext.copyOnWriteMonitor()
            .registerCollection(cowList, "cow-event-list");

        // Simulate write-heavy usage
        for (int i = 0; i < 10; i++) {
            cowList.add("entry-" + i);
            AsyncTestContext.copyOnWriteMonitor()
                .recordWrite(cowList, "cow-event-list");
        }
        // Only one read — ~90% write ratio triggers the detector
        cowList.size();
        AsyncTestContext.copyOnWriteMonitor()
            .recordRead(cowList, "cow-event-list");
    }

    /**
     * Demonstrates StringBuilder sharing detection.
     * StringBuilder mutated by multiple threads produces garbled output.
     */
    @AsyncTest(threads = 4, invocations = 20, detectStringBuilderIssues = true, timeoutMs = 5000)
    void testStringBuilderSharingDetection() {
        StringBuilder sharedSb = new StringBuilder();

        AsyncTestContext.stringBuilderMonitor()
            .registerBuilder(sharedSb, "log-builder");

        sharedSb.append("thread-").append(Thread.currentThread().getId()).append("|");
        AsyncTestContext.stringBuilderMonitor()
            .recordAppend(sharedSb, "log-builder");
    }

    // ============================================
    // PHASE 7: High-Level Concurrency Patterns
    // ============================================

    /**
     * Demonstrates HTTP client concurrency issue detection.
     * Detects unclosed HTTP responses and connection pool exhaustion.
     */
    @AsyncTest(threads = 4, invocations = 10, detectHttpClientIssues = true, timeoutMs = 5000)
    void testHttpClientConcurrencyDetection() {
        Object httpClient = new Object();
        Object httpRequest = new Object();
        Object httpResponse = new Object();

        AsyncTestContext.httpClientDetector()
            .recordClientCreated(httpClient, "api-client");

        AsyncTestContext.httpClientDetector()
            .recordRequestSent(httpRequest, "api-call");

        AsyncTestContext.httpClientDetector()
            .recordResponseReceived(httpResponse, "api-call");
    }

    /**
     * Demonstrates stream closing detection.
     * Detects InputStream/OutputStream instances not properly closed.
     */
    @AsyncTest(threads = 4, invocations = 10, detectStreamClosing = true, timeoutMs = 5000)
    void testStreamClosingDetection() throws Exception {
        InputStream inputStream = new ByteArrayInputStream(new byte[0]);

        AsyncTestContext.streamClosingDetector()
            .recordStreamOpened(inputStream, "data-input");

        try {
            // Simulate using the stream
            int data = inputStream.read();
        } finally {
            inputStream.close();
            AsyncTestContext.streamClosingDetector()
                .recordStreamClosed(inputStream, "data-input");
        }
    }

    /**
     * Demonstrates cache concurrency issue detection.
     * Detects HashMap used as cache without synchronization.
     */
    @AsyncTest(threads = 4, invocations = 10, detectCacheConcurrency = true, timeoutMs = 5000)
    void testCacheConcurrencyDetection() {
        Map<String, String> cache = new HashMap<>();

        AsyncTestContext.cacheConcurrencyDetector()
            .registerCache(cache, "user-cache");

        cache.put("user-" + Thread.currentThread().threadId(), "data");
        AsyncTestContext.cacheConcurrencyDetector()
            .recordPut(cache, "user-cache", 
                      "user-" + Thread.currentThread().threadId(), "data");

        String value = cache.get("key");
        AsyncTestContext.cacheConcurrencyDetector()
            .recordGet(cache, "user-cache", "key");
    }

    /**
     * Demonstrates CompletableFuture chain issue detection.
     * Detects missing exception handlers and unjoined futures.
     */
    @AsyncTest(threads = 4, invocations = 10, detectCompletableFutureChainIssues = true, timeoutMs = 5000)
    void testCompletableFutureChainDetection() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("test");
        CompletableFuture<String> chained = future.thenApply(s -> s.toUpperCase());

        AsyncTestContext.cfChainDetector()
            .recordFutureCreated(future, "async-operation");

        AsyncTestContext.cfChainDetector()
            .recordChainOperation(future, chained, "thenApply");

        AsyncTestContext.cfChainDetector()
            .recordExceptionally(future);

        String result = chained.join();
        AsyncTestContext.cfChainDetector()
            .recordFutureJoined(chained, "async-operation");
    }

    // ============================================
    // PHASE 6: Virtual Thread Concurrency (Java 21+)
    // ============================================

    /**
     * Demonstrates virtual thread stress testing with all currently available detectors.
     */
    @AsyncTest(threads = 6, invocations = 20,
               useVirtualThreads = true,
               detectAll = true,
               timeoutMs = 10000)
    void testVirtualThreadWithAllDetectors() {
        // Virtual threads with full detection — exercises the scheduler and concurrency
        // detectors that are available in the current published version.
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += i;
        }
        assertNotNull(AsyncTestContext.get());
    }

    /**
     * Phase 6.4: CPU-bound task detection on virtual threads.
     *
     * <p>Demonstrates the {@link VirtualThreadCpuBoundTaskDetector}: records tasks
     * that are CPU-bound and yield points for I/O-mixed work. Well-behaved tasks
     * that call {@code recordYieldPoint} before blocking operations should not
     * trigger violations.
     */
    @AsyncTest(threads = 4, invocations = 10,
               useVirtualThreads = true,
               detectVirtualThreadCpuBoundTasks = true,
               timeoutMs = 5000)
    void testVirtualThreadCpuBoundTaskDetection() throws InterruptedException {
        var detector = AsyncTestContext.virtualThreadCpuBoundTaskDetector();

        // Simulate an I/O-mixed task: record a yield point before any blocking call
        String taskId = detector.recordTaskStart("data-fetch");
        try {
            // Signal that we're about to do I/O (would park the virtual thread)
            detector.recordYieldPoint(taskId);
            Thread.sleep(1);  // simulates I/O — virtual thread unmounts here
        } finally {
            detector.recordTaskEnd(taskId);
        }

        // Short CPU burst within threshold — should be fine
        String cpuId = detector.recordTaskStart("quick-compute");
        try {
            int acc = 0;
            for (int i = 0; i < 1000; i++) acc += i;
            assertNotNull(acc);
        } finally {
            detector.recordTaskEnd(cpuId);
        }
    }

    /**
     * Phase 6.5: Carrier exhaustion detection for virtual threads.
     *
     * <p>Demonstrates the {@link VirtualThreadCarrierExhaustionDetector}: records
     * blocking start/end around operations that may pin virtual threads. A well-behaved
     * test that uses {@code ReentrantLock} (or limits concurrency) should not trigger
     * the exhaustion threshold.
     */
    @AsyncTest(threads = 4, invocations = 10,
               useVirtualThreads = true,
               detectVirtualThreadCarrierExhaustion = true,
               timeoutMs = 5000)
    void testVirtualThreadCarrierExhaustionDetection() throws InterruptedException {
        var detector = AsyncTestContext.virtualThreadCarrierExhaustionDetector();

        // Record a blocking region around an operation that parks the virtual thread.
        // Using ReentrantLock (not synchronized) so the virtual thread unmounts properly.
        detector.recordBlockingStart("reentrantlock-acquire");
        try {
            Thread.sleep(1);  // virtual thread parks; carrier is freed
        } finally {
            detector.recordBlockingEnd("reentrantlock-acquire");
        }
    }
}
