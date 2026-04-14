package com.github.asynctest;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable snapshot of all {@link AsyncTest} parameters.
 * Passed to {@link com.github.asynctest.runner.ConcurrencyRunner} as a single object
 * instead of an ever-growing parameter list.
 */
public final class AsyncTestConfig {

    // ---- Execution ----
    public final int threads;
    public final int invocations;
    public final boolean useVirtualThreads;
    public final long timeoutMs;
    public final String virtualThreadStressMode;

    // ---- Umbrella flag ----
    /** When {@code true}, every detector is treated as enabled. */
    public final boolean detectAll;

    // ---- Phase 1 ----
    public final boolean detectDeadlocks;
    public final boolean detectVisibility;
    public final boolean detectLivelocks;

    // ---- Phase 2 ----
    public final boolean detectFalseSharing;
    public final boolean detectWakeupIssues;
    public final boolean validateConstructorSafety;
    public final boolean detectABAProblem;
    public final boolean validateLockOrder;
    public final boolean monitorSynchronizers;
    public final boolean monitorThreadPool;
    public final boolean detectMemoryOrderingViolations;
    public final boolean monitorAsyncPipeline;
    public final boolean monitorReadWriteLockFairness;

    // ---- Phase 3 ----
    public final boolean detectRaceConditions;
    public final boolean detectThreadLocalLeaks;
    public final boolean detectBusyWaiting;
    public final boolean detectAtomicityViolations;
    public final boolean detectInterruptMishandling;

    // ---- Phase 2 Additional ----
    public final boolean monitorSemaphore;
    public final boolean detectCompletableFutureExceptions;
    public final boolean detectCompletableFutureCompletionLeaks;
    public final boolean detectVirtualThreadPinning;
    public final boolean detectThreadPoolDeadlocks;
    public final boolean detectConcurrentModifications;
    public final boolean detectLockLeaks;
    public final boolean detectSharedRandom;
    public final boolean detectBlockingQueueIssues;
    public final boolean detectConditionVariableIssues;
    public final boolean detectSimpleDateFormatIssues;
    public final boolean detectParallelStreamIssues;
    public final boolean detectResourceLeaks;

    // ---- Phase 2: Additional Concurrency ----
    public final boolean detectCountDownLatchIssues;
    public final boolean detectCyclicBarrierIssues;
    public final boolean detectReentrantLockIssues;
    public final boolean detectVolatileArrayIssues;
    public final boolean detectDoubleCheckedLocking;
    public final boolean detectWaitTimeout;

    // ---- Phase 2: Advanced Concurrency Utilities ----
    public final boolean detectPhaserIssues;
    public final boolean detectStampedLockIssues;
    public final boolean detectExchangerIssues;
    public final boolean detectScheduledExecutorIssues;
    public final boolean detectForkJoinPoolIssues;
    public final boolean detectThreadFactoryIssues;
    public final boolean detectThreadLeaks;
    public final boolean detectSleepInLock;
    public final boolean detectUnboundedQueue;
    public final boolean detectThreadStarvation;

    // ---- Phase 5: Thread-Safety of Common Types ----
    public final boolean detectCalendarIssues;
    public final boolean detectSharedCollections;
    public final boolean detectTimerIssues;
    public final boolean detectCopyOnWriteCollectionIssues;
    public final boolean detectStringBuilderIssues;

    // ---- Phase 6: Virtual Thread Concurrency (Java 21+) ----
    public final boolean detectStructuredConcurrencyIssues;
    public final boolean detectVirtualThreadContextLeaks;
    public final boolean detectScopedValueMisuse;

    // ---- Phase 7: High-Level Concurrency Patterns ----
    public final boolean detectHttpClientIssues;
    public final boolean detectStreamClosing;
    public final boolean detectCacheConcurrency;
    public final boolean detectCompletableFutureChainIssues;

    // ---- Benchmarking ----
    public final boolean enableBenchmarking;
    public final double benchmarkRegressionThreshold;
    public final boolean failOnBenchmarkRegression;

    private AsyncTestConfig(Builder b) {
        threads                        = b.threads;
        invocations                    = b.invocations;
        useVirtualThreads              = b.useVirtualThreads;
        timeoutMs                      = b.timeoutMs;
        virtualThreadStressMode        = b.virtualThreadStressMode;
        detectAll                      = b.detectAll;
        detectDeadlocks                = b.detectDeadlocks;
        detectVisibility               = b.detectVisibility;
        detectLivelocks                = b.detectLivelocks;
        detectFalseSharing             = b.detectFalseSharing;
        detectWakeupIssues             = b.detectWakeupIssues;
        validateConstructorSafety      = b.validateConstructorSafety;
        detectABAProblem               = b.detectABAProblem;
        validateLockOrder              = b.validateLockOrder;
        monitorSynchronizers           = b.monitorSynchronizers;
        monitorThreadPool              = b.monitorThreadPool;
        detectMemoryOrderingViolations = b.detectMemoryOrderingViolations;
        monitorAsyncPipeline           = b.monitorAsyncPipeline;
        monitorReadWriteLockFairness   = b.monitorReadWriteLockFairness;
        detectRaceConditions           = b.detectRaceConditions;
        detectThreadLocalLeaks         = b.detectThreadLocalLeaks;
        detectBusyWaiting              = b.detectBusyWaiting;
        detectAtomicityViolations      = b.detectAtomicityViolations;
        detectInterruptMishandling     = b.detectInterruptMishandling;
        monitorSemaphore               = b.monitorSemaphore;
        detectCompletableFutureExceptions = b.detectCompletableFutureExceptions;
        detectCompletableFutureCompletionLeaks = b.detectCompletableFutureCompletionLeaks;
        detectVirtualThreadPinning     = b.detectVirtualThreadPinning;
        detectThreadPoolDeadlocks      = b.detectThreadPoolDeadlocks;
        detectConcurrentModifications  = b.detectConcurrentModifications;
        detectLockLeaks                = b.detectLockLeaks;
        detectSharedRandom             = b.detectSharedRandom;
        detectBlockingQueueIssues      = b.detectBlockingQueueIssues;
        detectConditionVariableIssues  = b.detectConditionVariableIssues;
        detectSimpleDateFormatIssues   = b.detectSimpleDateFormatIssues;
        detectParallelStreamIssues     = b.detectParallelStreamIssues;
        detectResourceLeaks            = b.detectResourceLeaks;
        detectCountDownLatchIssues     = b.detectCountDownLatchIssues;
        detectCyclicBarrierIssues      = b.detectCyclicBarrierIssues;
        detectReentrantLockIssues      = b.detectReentrantLockIssues;
        detectVolatileArrayIssues      = b.detectVolatileArrayIssues;
        detectDoubleCheckedLocking     = b.detectDoubleCheckedLocking;
        detectWaitTimeout              = b.detectWaitTimeout;
        detectPhaserIssues             = b.detectPhaserIssues;
        detectStampedLockIssues        = b.detectStampedLockIssues;
        detectExchangerIssues          = b.detectExchangerIssues;
        detectScheduledExecutorIssues  = b.detectScheduledExecutorIssues;
        detectForkJoinPoolIssues       = b.detectForkJoinPoolIssues;
        detectThreadFactoryIssues      = b.detectThreadFactoryIssues;
        detectThreadLeaks              = b.detectThreadLeaks;
        detectSleepInLock              = b.detectSleepInLock;
        detectUnboundedQueue           = b.detectUnboundedQueue;
        detectThreadStarvation         = b.detectThreadStarvation;
        detectCalendarIssues           = b.detectCalendarIssues;
        detectSharedCollections        = b.detectSharedCollections;
        detectTimerIssues              = b.detectTimerIssues;
        detectCopyOnWriteCollectionIssues = b.detectCopyOnWriteCollectionIssues;
        detectStringBuilderIssues        = b.detectStringBuilderIssues;
        detectStructuredConcurrencyIssues = b.detectStructuredConcurrencyIssues;
        detectVirtualThreadContextLeaks  = b.detectVirtualThreadContextLeaks;
        detectScopedValueMisuse          = b.detectScopedValueMisuse;
        detectHttpClientIssues           = b.detectHttpClientIssues;
        detectStreamClosing              = b.detectStreamClosing;
        detectCacheConcurrency           = b.detectCacheConcurrency;
        detectCompletableFutureChainIssues = b.detectCompletableFutureChainIssues;
        enableBenchmarking             = b.enableBenchmarking;
        benchmarkRegressionThreshold   = b.benchmarkRegressionThreshold;
        failOnBenchmarkRegression      = b.failOnBenchmarkRegression;
    }

    /** Builds a config from an {@link AsyncTest} annotation instance. */
    public static AsyncTestConfig from(AsyncTest ann) {
        // Check for global benchmarking system property
        boolean globalBenchmarkingEnabled = Boolean.getBoolean("async-test.benchmarking.enabled");
        
        return builder()
            .threads(ann.threads())
            .invocations(ann.invocations())
            .useVirtualThreads(ann.useVirtualThreads())
            .timeoutMs(ann.timeoutMs())
            .virtualThreadStressMode(ann.virtualThreadStressMode())
            .detectAll(ann.detectAll())
            .detectDeadlocks(ann.detectDeadlocks())
            .detectVisibility(ann.detectVisibility())
            .detectLivelocks(ann.detectLivelocks())
            .detectFalseSharing(ann.detectFalseSharing())
            .detectWakeupIssues(ann.detectWakeupIssues())
            .validateConstructorSafety(ann.validateConstructorSafety())
            .detectABAProblem(ann.detectABAProblem())
            .validateLockOrder(ann.validateLockOrder())
            .monitorSynchronizers(ann.monitorSynchronizers())
            .monitorThreadPool(ann.monitorThreadPool())
            .detectMemoryOrderingViolations(ann.detectMemoryOrderingViolations())
            .monitorAsyncPipeline(ann.monitorAsyncPipeline())
            .monitorReadWriteLockFairness(ann.monitorReadWriteLockFairness())
            .detectRaceConditions(ann.detectRaceConditions())
            .detectThreadLocalLeaks(ann.detectThreadLocalLeaks())
            .detectBusyWaiting(ann.detectBusyWaiting())
            .detectAtomicityViolations(ann.detectAtomicityViolations())
            .detectInterruptMishandling(ann.detectInterruptMishandling())
            .monitorSemaphore(ann.monitorSemaphore())
            .detectCompletableFutureExceptions(ann.detectCompletableFutureExceptions())
            .detectCompletableFutureCompletionLeaks(ann.detectCompletableFutureCompletionLeaks())
            .detectVirtualThreadPinning(ann.detectVirtualThreadPinning())
            .detectThreadPoolDeadlocks(ann.detectThreadPoolDeadlocks())
            .detectConcurrentModifications(ann.detectConcurrentModifications())
            .detectLockLeaks(ann.detectLockLeaks())
            .detectSharedRandom(ann.detectSharedRandom())
            .detectBlockingQueueIssues(ann.detectBlockingQueueIssues())
            .detectConditionVariableIssues(ann.detectConditionVariableIssues())
            .detectSimpleDateFormatIssues(ann.detectSimpleDateFormatIssues())
            .detectParallelStreamIssues(ann.detectParallelStreamIssues())
            .detectResourceLeaks(ann.detectResourceLeaks())
            .detectCountDownLatchIssues(ann.detectCountDownLatchIssues())
            .detectCyclicBarrierIssues(ann.detectCyclicBarrierIssues())
            .detectReentrantLockIssues(ann.detectReentrantLockIssues())
            .detectVolatileArrayIssues(ann.detectVolatileArrayIssues())
            .detectDoubleCheckedLocking(ann.detectDoubleCheckedLocking())
            .detectWaitTimeout(ann.detectWaitTimeout())
            .detectPhaserIssues(ann.detectPhaserIssues())
            .detectStampedLockIssues(ann.detectStampedLockIssues())
            .detectExchangerIssues(ann.detectExchangerIssues())
            .detectScheduledExecutorIssues(ann.detectScheduledExecutorIssues())
            .detectForkJoinPoolIssues(ann.detectForkJoinPoolIssues())
            .detectThreadFactoryIssues(ann.detectThreadFactoryIssues())
            .detectThreadLeaks(ann.detectThreadLeaks())
            .detectSleepInLock(ann.detectSleepInLock())
            .detectUnboundedQueue(ann.detectUnboundedQueue())
            .detectThreadStarvation(ann.detectThreadStarvation())
            .detectCalendarIssues(ann.detectCalendarIssues())
            .detectSharedCollections(ann.detectSharedCollections())
            .detectTimerIssues(ann.detectTimerIssues())
            .detectCopyOnWriteCollectionIssues(ann.detectCopyOnWriteCollectionIssues())
            .detectStringBuilderIssues(ann.detectStringBuilderIssues())
            .detectStructuredConcurrencyIssues(ann.detectStructuredConcurrencyIssues())
            .detectVirtualThreadContextLeaks(ann.detectVirtualThreadContextLeaks())
            .detectScopedValueMisuse(ann.detectScopedValueMisuse())
            .detectHttpClientIssues(ann.detectHttpClientIssues())
            .detectStreamClosing(ann.detectStreamClosing())
            .detectCacheConcurrency(ann.detectCacheConcurrency())
            .detectCompletableFutureChainIssues(ann.detectCompletableFutureChainIssues())
            .enableBenchmarking(ann.enableBenchmarking() || globalBenchmarkingEnabled)
            .benchmarkRegressionThreshold(ann.benchmarkRegressionThreshold())
            .failOnBenchmarkRegression(ann.failOnBenchmarkRegression())
            .excludes(ann.excludes())
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int threads                        = 10;
        private int invocations                    = 100;
        private boolean useVirtualThreads          = true;
        private long timeoutMs                     = 5_000;
        private String virtualThreadStressMode     = "OFF";
        private boolean detectAll                  = false;
        private boolean detectDeadlocks            = true;
        private boolean detectVisibility           = false;
        private boolean detectLivelocks            = false;
        private boolean detectFalseSharing         = false;
        private boolean detectWakeupIssues         = false;
        private boolean validateConstructorSafety  = false;
        private boolean detectABAProblem           = false;
        private boolean validateLockOrder          = false;
        private boolean monitorSynchronizers       = false;
        private boolean monitorThreadPool          = false;
        private boolean detectMemoryOrderingViolations = false;
        private boolean monitorAsyncPipeline       = false;
        private boolean monitorReadWriteLockFairness = false;
        private boolean detectRaceConditions       = false;
        private boolean detectThreadLocalLeaks     = false;
        private boolean detectBusyWaiting          = false;
        private boolean detectAtomicityViolations  = false;
        private boolean detectInterruptMishandling = false;
        private boolean monitorSemaphore           = false;
        private boolean detectCompletableFutureExceptions = false;
        private boolean detectCompletableFutureCompletionLeaks = false;
        private boolean detectVirtualThreadPinning = false;
        private boolean detectThreadPoolDeadlocks = false;
        private boolean detectConcurrentModifications = false;
        private boolean detectLockLeaks = false;
        private boolean detectSharedRandom = false;
        private boolean detectBlockingQueueIssues = false;
        private boolean detectConditionVariableIssues = false;
        private boolean detectSimpleDateFormatIssues = false;
        private boolean detectParallelStreamIssues = false;
        private boolean detectResourceLeaks = false;
        private boolean detectCountDownLatchIssues = false;
        private boolean detectCyclicBarrierIssues = false;
        private boolean detectReentrantLockIssues = false;
        private boolean detectVolatileArrayIssues = false;
        private boolean detectDoubleCheckedLocking = false;
        private boolean detectWaitTimeout = false;
        private boolean detectPhaserIssues = false;
        private boolean detectStampedLockIssues = false;
        private boolean detectExchangerIssues = false;
        private boolean detectScheduledExecutorIssues = false;
        private boolean detectForkJoinPoolIssues = false;
        private boolean detectThreadFactoryIssues = false;
        private boolean detectThreadLeaks = false;
        private boolean detectSleepInLock = false;
        private boolean detectUnboundedQueue = false;
        private boolean detectThreadStarvation = false;
        private boolean detectCalendarIssues = false;
        private boolean detectSharedCollections = false;
        private boolean detectTimerIssues = false;
        private boolean detectCopyOnWriteCollectionIssues = false;
        private boolean detectStringBuilderIssues = false;
        private boolean detectStructuredConcurrencyIssues = false;
        private boolean detectVirtualThreadContextLeaks = false;
        private boolean detectScopedValueMisuse = false;
        private boolean detectHttpClientIssues = false;
        private boolean detectStreamClosing = false;
        private boolean detectCacheConcurrency = false;
        private boolean detectCompletableFutureChainIssues = false;
        private boolean enableBenchmarking = false;
        private double benchmarkRegressionThreshold = 0.2;
        private boolean failOnBenchmarkRegression = false;
        private Set<DetectorType> excludes = EnumSet.noneOf(DetectorType.class);

        public Builder threads(int v)                        { threads = v; return this; }
        public Builder invocations(int v)                    { invocations = v; return this; }
        public Builder useVirtualThreads(boolean v)          { useVirtualThreads = v; return this; }
        public Builder timeoutMs(long v)                     { timeoutMs = v; return this; }
        public Builder virtualThreadStressMode(String v)     { virtualThreadStressMode = v; return this; }
        public Builder detectAll(boolean v)                  { detectAll = v; return this; }
        public Builder detectDeadlocks(boolean v)            { detectDeadlocks = v; return this; }
        public Builder detectVisibility(boolean v)           { detectVisibility = v; return this; }
        public Builder detectLivelocks(boolean v)            { detectLivelocks = v; return this; }
        public Builder detectFalseSharing(boolean v)         { detectFalseSharing = v; return this; }
        public Builder detectWakeupIssues(boolean v)         { detectWakeupIssues = v; return this; }
        public Builder validateConstructorSafety(boolean v)  { validateConstructorSafety = v; return this; }
        public Builder detectABAProblem(boolean v)           { detectABAProblem = v; return this; }
        public Builder validateLockOrder(boolean v)          { validateLockOrder = v; return this; }
        public Builder monitorSynchronizers(boolean v)       { monitorSynchronizers = v; return this; }
        public Builder monitorThreadPool(boolean v)          { monitorThreadPool = v; return this; }
        public Builder detectMemoryOrderingViolations(boolean v) { detectMemoryOrderingViolations = v; return this; }
        public Builder monitorAsyncPipeline(boolean v)       { monitorAsyncPipeline = v; return this; }
        public Builder monitorReadWriteLockFairness(boolean v) { monitorReadWriteLockFairness = v; return this; }
        public Builder detectRaceConditions(boolean v)       { detectRaceConditions = v; return this; }
        public Builder detectThreadLocalLeaks(boolean v)     { detectThreadLocalLeaks = v; return this; }
        public Builder detectBusyWaiting(boolean v)          { detectBusyWaiting = v; return this; }
        public Builder detectAtomicityViolations(boolean v)  { detectAtomicityViolations = v; return this; }
        public Builder detectInterruptMishandling(boolean v) { detectInterruptMishandling = v; return this; }
        public Builder monitorSemaphore(boolean v)           { monitorSemaphore = v; return this; }
        public Builder detectCompletableFutureExceptions(boolean v) { detectCompletableFutureExceptions = v; return this; }
        public Builder detectCompletableFutureCompletionLeaks(boolean v) { detectCompletableFutureCompletionLeaks = v; return this; }
        public Builder detectVirtualThreadPinning(boolean v) { detectVirtualThreadPinning = v; return this; }
        public Builder detectThreadPoolDeadlocks(boolean v) { detectThreadPoolDeadlocks = v; return this; }
        public Builder detectConcurrentModifications(boolean v) { detectConcurrentModifications = v; return this; }
        public Builder detectLockLeaks(boolean v) { detectLockLeaks = v; return this; }
        public Builder detectSharedRandom(boolean v) { detectSharedRandom = v; return this; }
        public Builder detectBlockingQueueIssues(boolean v) { detectBlockingQueueIssues = v; return this; }
        public Builder detectConditionVariableIssues(boolean v) { detectConditionVariableIssues = v; return this; }
        public Builder detectSimpleDateFormatIssues(boolean v) { detectSimpleDateFormatIssues = v; return this; }
        public Builder detectParallelStreamIssues(boolean v) { detectParallelStreamIssues = v; return this; }
        public Builder detectResourceLeaks(boolean v) { detectResourceLeaks = v; return this; }
        public Builder detectCountDownLatchIssues(boolean v) { detectCountDownLatchIssues = v; return this; }
        public Builder detectCyclicBarrierIssues(boolean v) { detectCyclicBarrierIssues = v; return this; }
        public Builder detectReentrantLockIssues(boolean v) { detectReentrantLockIssues = v; return this; }
        public Builder detectVolatileArrayIssues(boolean v) { detectVolatileArrayIssues = v; return this; }
        public Builder detectDoubleCheckedLocking(boolean v) { detectDoubleCheckedLocking = v; return this; }
        public Builder detectWaitTimeout(boolean v) { detectWaitTimeout = v; return this; }
        public Builder detectPhaserIssues(boolean v) { detectPhaserIssues = v; return this; }
        public Builder detectStampedLockIssues(boolean v) { detectStampedLockIssues = v; return this; }
        public Builder detectExchangerIssues(boolean v) { detectExchangerIssues = v; return this; }
        public Builder detectScheduledExecutorIssues(boolean v) { detectScheduledExecutorIssues = v; return this; }
        public Builder detectForkJoinPoolIssues(boolean v) { detectForkJoinPoolIssues = v; return this; }
        public Builder detectThreadFactoryIssues(boolean v) { detectThreadFactoryIssues = v; return this; }
        public Builder detectThreadLeaks(boolean v) { detectThreadLeaks = v; return this; }
        public Builder detectSleepInLock(boolean v) { detectSleepInLock = v; return this; }
        public Builder detectUnboundedQueue(boolean v) { detectUnboundedQueue = v; return this; }
        public Builder detectThreadStarvation(boolean v) { detectThreadStarvation = v; return this; }
        public Builder detectCalendarIssues(boolean v) { detectCalendarIssues = v; return this; }
        public Builder detectSharedCollections(boolean v) { detectSharedCollections = v; return this; }
        public Builder detectTimerIssues(boolean v) { detectTimerIssues = v; return this; }
        public Builder detectCopyOnWriteCollectionIssues(boolean v) { detectCopyOnWriteCollectionIssues = v; return this; }
        public Builder detectStringBuilderIssues(boolean v)           { detectStringBuilderIssues = v; return this; }
        public Builder detectStructuredConcurrencyIssues(boolean v)    { detectStructuredConcurrencyIssues = v; return this; }
        public Builder detectVirtualThreadContextLeaks(boolean v)      { detectVirtualThreadContextLeaks = v; return this; }
        public Builder detectScopedValueMisuse(boolean v)              { detectScopedValueMisuse = v; return this; }
        public Builder detectHttpClientIssues(boolean v)               { detectHttpClientIssues = v; return this; }
        public Builder detectStreamClosing(boolean v)                  { detectStreamClosing = v; return this; }
        public Builder detectCacheConcurrency(boolean v)               { detectCacheConcurrency = v; return this; }
        public Builder detectCompletableFutureChainIssues(boolean v)   { detectCompletableFutureChainIssues = v; return this; }
        public Builder enableBenchmarking(boolean v) { enableBenchmarking = v; return this; }
        public Builder benchmarkRegressionThreshold(double v) { benchmarkRegressionThreshold = v; return this; }
        public Builder failOnBenchmarkRegression(boolean v) { failOnBenchmarkRegression = v; return this; }

        public Builder excludes(DetectorType[] v) {
            if (v != null && v.length > 0) {
                this.excludes.addAll(Arrays.asList(v));
            }
            return this;
        }

        public AsyncTestConfig build() {
            if (detectAll) {
                if (!excludes.contains(DetectorType.DEADLOCKS)) detectDeadlocks = true;
                    else detectDeadlocks = false;
                if (!excludes.contains(DetectorType.VISIBILITY)) detectVisibility = true;
                    else detectVisibility = false;
                if (!excludes.contains(DetectorType.LIVELOCKS)) detectLivelocks = true;
                    else detectLivelocks = false;
                if (!excludes.contains(DetectorType.FALSE_SHARING)) detectFalseSharing = true;
                    else detectFalseSharing = false;
                if (!excludes.contains(DetectorType.WAKEUP_ISSUES)) detectWakeupIssues = true;
                    else detectWakeupIssues = false;
                if (!excludes.contains(DetectorType.CONSTRUCTOR_SAFETY)) validateConstructorSafety = true;
                    else validateConstructorSafety = false;
                if (!excludes.contains(DetectorType.ABA_PROBLEM)) detectABAProblem = true;
                    else detectABAProblem = false;
                if (!excludes.contains(DetectorType.LOCK_ORDER)) validateLockOrder = true;
                    else validateLockOrder = false;
                if (!excludes.contains(DetectorType.SYNCHRONIZERS)) monitorSynchronizers = true;
                    else monitorSynchronizers = false;
                if (!excludes.contains(DetectorType.THREAD_POOL)) monitorThreadPool = true;
                    else monitorThreadPool = false;
                if (!excludes.contains(DetectorType.MEMORY_ORDERING)) detectMemoryOrderingViolations = true;
                    else detectMemoryOrderingViolations = false;
                if (!excludes.contains(DetectorType.ASYNC_PIPELINE)) monitorAsyncPipeline = true;
                    else monitorAsyncPipeline = false;
                if (!excludes.contains(DetectorType.READ_WRITE_LOCK_FAIRNESS)) monitorReadWriteLockFairness = true;
                    else monitorReadWriteLockFairness = false;
                if (!excludes.contains(DetectorType.SEMAPHORE)) monitorSemaphore = true;
                    else monitorSemaphore = false;
                if (!excludes.contains(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS)) detectCompletableFutureExceptions = true;
                    else detectCompletableFutureExceptions = false;
                if (!excludes.contains(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS)) detectCompletableFutureCompletionLeaks = true;
                    else detectCompletableFutureCompletionLeaks = false;
                if (!excludes.contains(DetectorType.VIRTUAL_THREAD_PINNING)) detectVirtualThreadPinning = true;
                    else detectVirtualThreadPinning = false;
                if (!excludes.contains(DetectorType.THREAD_POOL_DEADLOCK)) detectThreadPoolDeadlocks = true;
                    else detectThreadPoolDeadlocks = false;
                if (!excludes.contains(DetectorType.CONCURRENT_MODIFICATIONS)) detectConcurrentModifications = true;
                    else detectConcurrentModifications = false;
                if (!excludes.contains(DetectorType.LOCK_LEAKS)) detectLockLeaks = true;
                    else detectLockLeaks = false;
                if (!excludes.contains(DetectorType.SHARED_RANDOM)) detectSharedRandom = true;
                    else detectSharedRandom = false;
                if (!excludes.contains(DetectorType.BLOCKING_QUEUE)) detectBlockingQueueIssues = true;
                    else detectBlockingQueueIssues = false;
                if (!excludes.contains(DetectorType.CONDITION_VARIABLES)) detectConditionVariableIssues = true;
                    else detectConditionVariableIssues = false;
                if (!excludes.contains(DetectorType.SIMPLE_DATE_FORMAT)) detectSimpleDateFormatIssues = true;
                    else detectSimpleDateFormatIssues = false;
                if (!excludes.contains(DetectorType.PARALLEL_STREAMS)) detectParallelStreamIssues = true;
                    else detectParallelStreamIssues = false;
                if (!excludes.contains(DetectorType.RESOURCE_LEAKS)) detectResourceLeaks = true;
                    else detectResourceLeaks = false;
                if (!excludes.contains(DetectorType.COUNTDOWN_LATCH)) detectCountDownLatchIssues = true;
                    else detectCountDownLatchIssues = false;
                if (!excludes.contains(DetectorType.CYCLIC_BARRIER)) detectCyclicBarrierIssues = true;
                    else detectCyclicBarrierIssues = false;
                if (!excludes.contains(DetectorType.REENTRANT_LOCK)) detectReentrantLockIssues = true;
                    else detectReentrantLockIssues = false;
                if (!excludes.contains(DetectorType.VOLATILE_ARRAY)) detectVolatileArrayIssues = true;
                    else detectVolatileArrayIssues = false;
                if (!excludes.contains(DetectorType.DOUBLE_CHECKED_LOCKING)) detectDoubleCheckedLocking = true;
                    else detectDoubleCheckedLocking = false;
                if (!excludes.contains(DetectorType.WAIT_TIMEOUT)) detectWaitTimeout = true;
                    else detectWaitTimeout = false;
                if (!excludes.contains(DetectorType.PHASER)) detectPhaserIssues = true;
                    else detectPhaserIssues = false;
                if (!excludes.contains(DetectorType.STAMPED_LOCK)) detectStampedLockIssues = true;
                    else detectStampedLockIssues = false;
                if (!excludes.contains(DetectorType.EXCHANGER)) detectExchangerIssues = true;
                    else detectExchangerIssues = false;
                if (!excludes.contains(DetectorType.SCHEDULED_EXECUTOR)) detectScheduledExecutorIssues = true;
                    else detectScheduledExecutorIssues = false;
                if (!excludes.contains(DetectorType.FORK_JOIN_POOL)) detectForkJoinPoolIssues = true;
                    else detectForkJoinPoolIssues = false;
                if (!excludes.contains(DetectorType.THREAD_FACTORY)) detectThreadFactoryIssues = true;
                    else detectThreadFactoryIssues = false;
                if (!excludes.contains(DetectorType.THREAD_LEAKS)) detectThreadLeaks = true;
                    else detectThreadLeaks = false;
                if (!excludes.contains(DetectorType.SLEEP_IN_LOCK)) detectSleepInLock = true;
                    else detectSleepInLock = false;
                if (!excludes.contains(DetectorType.UNBOUNDED_QUEUE)) detectUnboundedQueue = true;
                    else detectUnboundedQueue = false;
                if (!excludes.contains(DetectorType.THREAD_STARVATION)) detectThreadStarvation = true;
                    else detectThreadStarvation = false;
                if (!excludes.contains(DetectorType.CALENDAR)) detectCalendarIssues = true;
                    else detectCalendarIssues = false;
                if (!excludes.contains(DetectorType.SHARED_COLLECTIONS)) detectSharedCollections = true;
                    else detectSharedCollections = false;
                if (!excludes.contains(DetectorType.TIMER)) detectTimerIssues = true;
                    else detectTimerIssues = false;
                if (!excludes.contains(DetectorType.COPY_ON_WRITE_COLLECTIONS)) detectCopyOnWriteCollectionIssues = true;
                    else detectCopyOnWriteCollectionIssues = false;
                if (!excludes.contains(DetectorType.STRING_BUILDER)) detectStringBuilderIssues = true;
                    else detectStringBuilderIssues = false;
                if (!excludes.contains(DetectorType.STRUCTURED_CONCURRENCY)) detectStructuredConcurrencyIssues = true;
                    else detectStructuredConcurrencyIssues = false;
                if (!excludes.contains(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS)) detectVirtualThreadContextLeaks = true;
                    else detectVirtualThreadContextLeaks = false;
                if (!excludes.contains(DetectorType.SCOPED_VALUE)) detectScopedValueMisuse = true;
                    else detectScopedValueMisuse = false;
                if (!excludes.contains(DetectorType.RACE_CONDITIONS)) detectRaceConditions = true;
                    else detectRaceConditions = false;
                if (!excludes.contains(DetectorType.THREAD_LOCAL_LEAKS)) detectThreadLocalLeaks = true;
                    else detectThreadLocalLeaks = false;
                if (!excludes.contains(DetectorType.BUSY_WAITING)) detectBusyWaiting = true;
                    else detectBusyWaiting = false;
                if (!excludes.contains(DetectorType.ATOMICITY_VIOLATIONS)) detectAtomicityViolations = true;
                    else detectAtomicityViolations = false;
                if (!excludes.contains(DetectorType.INTERRUPT_MISHANDLING)) detectInterruptMishandling = true;
                    else detectInterruptMishandling = false;
                if (!excludes.contains(DetectorType.HTTP_CLIENT)) detectHttpClientIssues = true;
                    else detectHttpClientIssues = false;
                if (!excludes.contains(DetectorType.STREAM_CLOSING)) detectStreamClosing = true;
                    else detectStreamClosing = false;
                if (!excludes.contains(DetectorType.CACHE_CONCURRENCY)) detectCacheConcurrency = true;
                    else detectCacheConcurrency = false;
                if (!excludes.contains(DetectorType.COMPLETABLEFUTURE_CHAIN)) detectCompletableFutureChainIssues = true;
                    else detectCompletableFutureChainIssues = false;
            } else {
                // If detectAll is false, we still respect explicit enables,
                // but we also apply excludes for consistency.
                if (excludes.contains(DetectorType.DEADLOCKS)) detectDeadlocks = false;
                if (excludes.contains(DetectorType.VISIBILITY)) detectVisibility = false;
                if (excludes.contains(DetectorType.LIVELOCKS)) detectLivelocks = false;
                if (excludes.contains(DetectorType.FALSE_SHARING)) detectFalseSharing = false;
                if (excludes.contains(DetectorType.WAKEUP_ISSUES)) detectWakeupIssues = false;
                if (excludes.contains(DetectorType.CONSTRUCTOR_SAFETY)) validateConstructorSafety = false;
                if (excludes.contains(DetectorType.ABA_PROBLEM)) detectABAProblem = false;
                if (excludes.contains(DetectorType.LOCK_ORDER)) validateLockOrder = false;
                if (excludes.contains(DetectorType.SYNCHRONIZERS)) monitorSynchronizers = false;
                if (excludes.contains(DetectorType.THREAD_POOL)) monitorThreadPool = false;
                if (excludes.contains(DetectorType.MEMORY_ORDERING)) detectMemoryOrderingViolations = false;
                if (excludes.contains(DetectorType.ASYNC_PIPELINE)) monitorAsyncPipeline = false;
                if (excludes.contains(DetectorType.READ_WRITE_LOCK_FAIRNESS)) monitorReadWriteLockFairness = false;
                if (excludes.contains(DetectorType.SEMAPHORE)) monitorSemaphore = false;
                if (excludes.contains(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS)) detectCompletableFutureExceptions = false;
                if (excludes.contains(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS)) detectCompletableFutureCompletionLeaks = false;
                if (excludes.contains(DetectorType.VIRTUAL_THREAD_PINNING)) detectVirtualThreadPinning = false;
                if (excludes.contains(DetectorType.THREAD_POOL_DEADLOCK)) detectThreadPoolDeadlocks = false;
                if (excludes.contains(DetectorType.CONCURRENT_MODIFICATIONS)) detectConcurrentModifications = false;
                if (excludes.contains(DetectorType.LOCK_LEAKS)) detectLockLeaks = false;
                if (excludes.contains(DetectorType.SHARED_RANDOM)) detectSharedRandom = false;
                if (excludes.contains(DetectorType.BLOCKING_QUEUE)) detectBlockingQueueIssues = false;
                if (excludes.contains(DetectorType.CONDITION_VARIABLES)) detectConditionVariableIssues = false;
                if (excludes.contains(DetectorType.SIMPLE_DATE_FORMAT)) detectSimpleDateFormatIssues = false;
                if (excludes.contains(DetectorType.PARALLEL_STREAMS)) detectParallelStreamIssues = false;
                if (excludes.contains(DetectorType.RESOURCE_LEAKS)) detectResourceLeaks = false;
                if (excludes.contains(DetectorType.COUNTDOWN_LATCH)) detectCountDownLatchIssues = false;
                if (excludes.contains(DetectorType.CYCLIC_BARRIER)) detectCyclicBarrierIssues = false;
                if (excludes.contains(DetectorType.REENTRANT_LOCK)) detectReentrantLockIssues = false;
                if (excludes.contains(DetectorType.VOLATILE_ARRAY)) detectVolatileArrayIssues = false;
                if (excludes.contains(DetectorType.DOUBLE_CHECKED_LOCKING)) detectDoubleCheckedLocking = false;
                if (excludes.contains(DetectorType.WAIT_TIMEOUT)) detectWaitTimeout = false;
                if (excludes.contains(DetectorType.PHASER)) detectPhaserIssues = false;
                if (excludes.contains(DetectorType.STAMPED_LOCK)) detectStampedLockIssues = false;
                if (excludes.contains(DetectorType.EXCHANGER)) detectExchangerIssues = false;
                if (excludes.contains(DetectorType.SCHEDULED_EXECUTOR)) detectScheduledExecutorIssues = false;
                if (excludes.contains(DetectorType.FORK_JOIN_POOL)) detectForkJoinPoolIssues = false;
                if (excludes.contains(DetectorType.THREAD_FACTORY)) detectThreadFactoryIssues = false;
                if (excludes.contains(DetectorType.THREAD_LEAKS)) detectThreadLeaks = false;
                if (excludes.contains(DetectorType.SLEEP_IN_LOCK)) detectSleepInLock = false;
                if (excludes.contains(DetectorType.UNBOUNDED_QUEUE)) detectUnboundedQueue = false;
                if (excludes.contains(DetectorType.THREAD_STARVATION)) detectThreadStarvation = false;
                if (excludes.contains(DetectorType.CALENDAR)) detectCalendarIssues = false;
                if (excludes.contains(DetectorType.SHARED_COLLECTIONS)) detectSharedCollections = false;
                if (excludes.contains(DetectorType.TIMER)) detectTimerIssues = false;
                if (excludes.contains(DetectorType.COPY_ON_WRITE_COLLECTIONS)) detectCopyOnWriteCollectionIssues = false;
                if (excludes.contains(DetectorType.STRING_BUILDER)) detectStringBuilderIssues = false;
                if (excludes.contains(DetectorType.STRUCTURED_CONCURRENCY)) detectStructuredConcurrencyIssues = false;
                if (excludes.contains(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS)) detectVirtualThreadContextLeaks = false;
                if (excludes.contains(DetectorType.SCOPED_VALUE)) detectScopedValueMisuse = false;
                if (excludes.contains(DetectorType.HTTP_CLIENT)) detectHttpClientIssues = false;
                if (excludes.contains(DetectorType.STREAM_CLOSING)) detectStreamClosing = false;
                if (excludes.contains(DetectorType.CACHE_CONCURRENCY)) detectCacheConcurrency = false;
                if (excludes.contains(DetectorType.COMPLETABLEFUTURE_CHAIN)) detectCompletableFutureChainIssues = false;
                if (excludes.contains(DetectorType.RACE_CONDITIONS)) detectRaceConditions = false;
                if (excludes.contains(DetectorType.THREAD_LOCAL_LEAKS)) detectThreadLocalLeaks = false;
                if (excludes.contains(DetectorType.BUSY_WAITING)) detectBusyWaiting = false;
                if (excludes.contains(DetectorType.ATOMICITY_VIOLATIONS)) detectAtomicityViolations = false;
                if (excludes.contains(DetectorType.INTERRUPT_MISHANDLING)) detectInterruptMishandling = false;
            }
            return new AsyncTestConfig(this);
        }
    }
}
