package se.deversity.asynctest;

import se.deversity.asynctest.diagnostics.*;

import java.util.List;
import java.util.function.Function;

/**
 * Per-test context that makes Phase 2 detector instances accessible to test code
 * via static accessor methods and manages the per-thread {@link ThreadLocal} lifecycle.
 *
 * <p>Detector instantiation and analysis are delegated to {@link DetectorRegistry},
 * keeping this class focused on two concerns:
 * <ol>
 *   <li>ThreadLocal install / uninstall (called by {@link se.deversity.asynctest.runner.ConcurrencyRunner})</li>
 *   <li>Public static accessor methods (the user-facing API)</li>
 * </ol>
 *
 * <p>Usage inside an {@code @AsyncTest} method:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectFalseSharing = true)
 * void myTest() {
 *     AsyncTestContext.falseSharingDetector()
 *         .recordFieldAccess(this, "counter", int.class);
 * }
 * }</pre>
 *
 * <p>After the test run completes (or times out), the runner calls {@link #analyzeAll()}
 * and prints any Phase 2 reports that have issues.
 */
public final class AsyncTestContext {

    private static final ThreadLocal<AsyncTestContext> CURRENT = new ThreadLocal<>();

    /** Holds detector instances; extracted to keep this class small. */
    private final DetectorRegistry registry;

    // ---- Package-private field accessors for DetectorRegistry (used by tests) ----
    // These are forwarded to the registry so existing test code that accesses
    // ctx.lockLeakDetector etc. continues to work without modification.

    final FalseSharingDetector       falseSharingDetector;
    final WakeupDetector             wakeupDetector;
    final ConstructorSafetyValidator constructorSafetyValidator;
    final ABAProblemDetector         abaProblemDetector;
    final LockOrderValidator         lockOrderValidator;
    final SynchronizerMonitor        synchronizerMonitor;
    final ThreadPoolMonitor          threadPoolMonitor;
    final MemoryOrderingMonitor      memoryOrderingMonitor;
    final PipelineMonitor            pipelineMonitor;
    final ReadWriteLockMonitor       readWriteLockMonitor;
    final SemaphoreMisuseDetector    semaphoreMisuseDetector;
    final CompletableFutureExceptionDetector completableFutureExceptionDetector;
    final CompletableFutureCompletionLeakDetector completableFutureCompletionLeakDetector;
    final VirtualThreadPinningDetector virtualThreadPinningDetector;
    final ThreadPoolDeadlockDetector threadPoolDeadlockDetector;
    final ConcurrentModificationDetector concurrentModificationDetector;
    final LockLeakDetector lockLeakDetector;
    final SharedRandomDetector sharedRandomDetector;
    final BlockingQueueDetector blockingQueueDetector;
    final ConditionVariableDetector conditionVariableDetector;
    final SimpleDateFormatDetector simpleDateFormatDetector;
    final ParallelStreamDetector parallelStreamDetector;
    final ResourceLeakDetector resourceLeakDetector;
    final CountDownLatchDetector countDownLatchDetector;
    final CyclicBarrierDetector cyclicBarrierDetector;
    final ReentrantLockDetector reentrantLockDetector;
    final VolatileArrayDetector volatileArrayDetector;
    final DoubleCheckedLockingDetector doubleCheckedLockingDetector;
    final WaitTimeoutDetector waitTimeoutDetector;
    final LockContentionDetector lockContentionDetector;
    final SynchronizedNonFinalDetector synchronizedNonFinalDetector;
    final MissedSignalDetector missedSignalDetector;
    final LazyInitRaceDetector lazyInitRaceDetector;
    final PhaserDetector phaserDetector;
    final StampedLockDetector stampedLockDetector;
    final ExchangerDetector exchangerDetector;
    final ScheduledExecutorDetector scheduledExecutorDetector;
    final ForkJoinPoolDetector forkJoinPoolDetector;
    final ThreadFactoryDetector threadFactoryDetector;
    final ThreadLeakDetector threadLeakDetector;
    final SleepInLockDetector sleepInLockDetector;
    final UnboundedQueueDetector unboundedQueueDetector;
    final ThreadStarvationDetector threadStarvationDetector;
    final CalendarDetector calendarDetector;
    final SharedCollectionDetector sharedCollectionDetector;
    final TimerDetector timerDetector;
    final CopyOnWriteCollectionDetector copyOnWriteCollectionDetector;
    final StringBuilderDetector stringBuilderDetector;
    final StructuredConcurrencyMisuseDetector    structuredConcurrencyMisuseDetector;
    final VirtualThreadContextLeakDetector       virtualThreadContextLeakDetector;
    final ScopedValueMisuseDetector              scopedValueMisuseDetector;
    final VirtualThreadCpuBoundTaskDetector      virtualThreadCpuBoundTaskDetector;
    final VirtualThreadCarrierExhaustionDetector virtualThreadCarrierExhaustionDetector;
    final HttpClientConcurrencyDetector          httpClientConcurrencyDetector;
    final StreamClosingDetector               streamClosingDetector;
    final CacheConcurrencyDetector            cacheConcurrencyDetector;
    final CompletableFutureChainDetector      completableFutureChainDetector;
    final ExecutorShutdownDetector            executorShutdownDetector;
    final MutableMapKeyDetector               mutableMapKeyDetector;
    final NestedMonitorLockoutDetector        nestedMonitorLockoutDetector;
    final LockDowngradeDetector               lockDowngradeDetector;
    final InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseDetector;
    final UncommittedChangesDetector           uncommittedChangesDetector;

    // ---- Phase 10: API Traps & Subtle Concurrency Bugs ----
    final ThreadLocalContaminationDetector         threadLocalContaminationDetector;
    final AtomicNonAtomicUpdateDetector            atomicNonAtomicUpdateDetector;
    final SynchronizedCollectionIterationDetector  synchronizedCollectionIterationDetector;
    final SharedFormatterDetector                  sharedFormatterDetector;
    final ConcurrentMapComputeRecursionDetector    concurrentMapComputeRecursionDetector;
    final SynchronizedOnLiteralDetector            synchronizedOnLiteralDetector;
    final PublicLockExposureDetector               publicLockExposureDetector;
    final ForkJoinTaskBlockingDetector             forkJoinTaskBlockingDetector;
    final OptimisticReadValidationDetector         optimisticReadValidationDetector;
    final CompletableFutureCommonPoolBlockingDetector cfCommonPoolBlockingDetector;

    // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----
    final SharedMatcherDetector        sharedMatcherDetector;
    final SharedDecimalFormatDetector  sharedDecimalFormatDetector;
    final WeakReferenceRaceDetector    weakReferenceRaceDetector;
    final StatefulLambdaDetector       statefulLambdaDetector;
    final SharedMessageDigestDetector  sharedMessageDigestDetector;

    // ---- Phase 12: Operational & Hygiene Concurrency Issues ----
    final InterruptSwallowingDetector       interruptSwallowingDetector;
    final MdcContextLeakDetector            mdcContextLeakDetector;
    final SystemPropertyMutationDetector    systemPropertyMutationDetector;
    final FutureIgnoredDetector             futureIgnoredDetector;
    final ExplicitGcDetector                explicitGcDetector;
    final DeprecatedThreadApiDetector       deprecatedThreadApiDetector;
    final SharedXmlParserDetector           sharedXmlParserDetector;
    final BoxedPrimitiveLockDetector        boxedPrimitiveLockDetector;
    final SharedTimeZoneDetector            sharedTimeZoneDetector;
    final UncaughtExceptionHandlerDetector  uncaughtExceptionHandlerDetector;

    public AsyncTestContext(AsyncTestConfig cfg) {
        this.registry = new DetectorRegistry(cfg);
        // Mirror registry references so package-private field access still works
        // (e.g. ctx.lockLeakDetector in tests). This is a thin delegation shim —
        // zero allocation overhead compared to the old design.
        falseSharingDetector              = registry.falseSharingDetector;
        wakeupDetector                    = registry.wakeupDetector;
        constructorSafetyValidator        = registry.constructorSafetyValidator;
        abaProblemDetector                = registry.abaProblemDetector;
        lockOrderValidator                = registry.lockOrderValidator;
        synchronizerMonitor               = registry.synchronizerMonitor;
        threadPoolMonitor                 = registry.threadPoolMonitor;
        memoryOrderingMonitor             = registry.memoryOrderingMonitor;
        pipelineMonitor                   = registry.pipelineMonitor;
        readWriteLockMonitor              = registry.readWriteLockMonitor;
        semaphoreMisuseDetector           = registry.semaphoreMisuseDetector;
        completableFutureExceptionDetector = registry.completableFutureExceptionDetector;
        completableFutureCompletionLeakDetector = registry.completableFutureCompletionLeakDetector;
        virtualThreadPinningDetector      = registry.virtualThreadPinningDetector;
        threadPoolDeadlockDetector        = registry.threadPoolDeadlockDetector;
        concurrentModificationDetector    = registry.concurrentModificationDetector;
        lockLeakDetector                  = registry.lockLeakDetector;
        sharedRandomDetector              = registry.sharedRandomDetector;
        blockingQueueDetector             = registry.blockingQueueDetector;
        conditionVariableDetector         = registry.conditionVariableDetector;
        simpleDateFormatDetector          = registry.simpleDateFormatDetector;
        parallelStreamDetector            = registry.parallelStreamDetector;
        resourceLeakDetector              = registry.resourceLeakDetector;
        countDownLatchDetector            = registry.countDownLatchDetector;
        cyclicBarrierDetector             = registry.cyclicBarrierDetector;
        reentrantLockDetector             = registry.reentrantLockDetector;
        volatileArrayDetector             = registry.volatileArrayDetector;
        doubleCheckedLockingDetector      = registry.doubleCheckedLockingDetector;
        waitTimeoutDetector               = registry.waitTimeoutDetector;
        lockContentionDetector            = registry.lockContentionDetector;
        synchronizedNonFinalDetector      = registry.synchronizedNonFinalDetector;
        missedSignalDetector              = registry.missedSignalDetector;
        lazyInitRaceDetector              = registry.lazyInitRaceDetector;
        phaserDetector                    = registry.phaserDetector;
        stampedLockDetector               = registry.stampedLockDetector;
        exchangerDetector                 = registry.exchangerDetector;
        scheduledExecutorDetector         = registry.scheduledExecutorDetector;
        forkJoinPoolDetector              = registry.forkJoinPoolDetector;
        threadFactoryDetector             = registry.threadFactoryDetector;
        threadLeakDetector                = registry.threadLeakDetector;
        sleepInLockDetector               = registry.sleepInLockDetector;
        unboundedQueueDetector            = registry.unboundedQueueDetector;
        threadStarvationDetector          = registry.threadStarvationDetector;
        calendarDetector                  = registry.calendarDetector;
        sharedCollectionDetector          = registry.sharedCollectionDetector;
        timerDetector                     = registry.timerDetector;
        copyOnWriteCollectionDetector          = registry.copyOnWriteCollectionDetector;
        stringBuilderDetector                  = registry.stringBuilderDetector;
        structuredConcurrencyMisuseDetector      = registry.structuredConcurrencyMisuseDetector;
        virtualThreadContextLeakDetector         = registry.virtualThreadContextLeakDetector;
        scopedValueMisuseDetector                = registry.scopedValueMisuseDetector;
        virtualThreadCpuBoundTaskDetector        = registry.virtualThreadCpuBoundTaskDetector;
        virtualThreadCarrierExhaustionDetector   = registry.virtualThreadCarrierExhaustionDetector;
        httpClientConcurrencyDetector            = registry.httpClientConcurrencyDetector;
        streamClosingDetector                  = registry.streamClosingDetector;
        cacheConcurrencyDetector               = registry.cacheConcurrencyDetector;
        completableFutureChainDetector         = registry.completableFutureChainDetector;
        executorShutdownDetector               = registry.executorShutdownDetector;
        mutableMapKeyDetector                  = registry.mutableMapKeyDetector;
        nestedMonitorLockoutDetector           = registry.nestedMonitorLockoutDetector;
        lockDowngradeDetector                  = registry.lockDowngradeDetector;
        inheritableThreadLocalMisuseDetector   = registry.inheritableThreadLocalMisuseDetector;
        uncommittedChangesDetector             = registry.uncommittedChangesDetector;
        threadLocalContaminationDetector       = registry.threadLocalContaminationDetector;
        atomicNonAtomicUpdateDetector          = registry.atomicNonAtomicUpdateDetector;
        synchronizedCollectionIterationDetector = registry.synchronizedCollectionIterationDetector;
        sharedFormatterDetector                = registry.sharedFormatterDetector;
        concurrentMapComputeRecursionDetector  = registry.concurrentMapComputeRecursionDetector;
        synchronizedOnLiteralDetector          = registry.synchronizedOnLiteralDetector;
        publicLockExposureDetector             = registry.publicLockExposureDetector;
        forkJoinTaskBlockingDetector           = registry.forkJoinTaskBlockingDetector;
        optimisticReadValidationDetector       = registry.optimisticReadValidationDetector;
        cfCommonPoolBlockingDetector           = registry.cfCommonPoolBlockingDetector;
        sharedMatcherDetector                  = registry.sharedMatcherDetector;
        sharedDecimalFormatDetector            = registry.sharedDecimalFormatDetector;
        weakReferenceRaceDetector              = registry.weakReferenceRaceDetector;
        statefulLambdaDetector                 = registry.statefulLambdaDetector;
        sharedMessageDigestDetector            = registry.sharedMessageDigestDetector;
        interruptSwallowingDetector            = registry.interruptSwallowingDetector;
        mdcContextLeakDetector                 = registry.mdcContextLeakDetector;
        systemPropertyMutationDetector         = registry.systemPropertyMutationDetector;
        futureIgnoredDetector                  = registry.futureIgnoredDetector;
        explicitGcDetector                     = registry.explicitGcDetector;
        deprecatedThreadApiDetector            = registry.deprecatedThreadApiDetector;
        sharedXmlParserDetector                = registry.sharedXmlParserDetector;
        boxedPrimitiveLockDetector             = registry.boxedPrimitiveLockDetector;
        sharedTimeZoneDetector                 = registry.sharedTimeZoneDetector;
        uncaughtExceptionHandlerDetector       = registry.uncaughtExceptionHandlerDetector;
    }

    // ---- Lifecycle (called by ConcurrencyRunner) ----

    /** Installs {@code ctx} into the calling thread's ThreadLocal. */
    public static void install(AsyncTestContext ctx) {
        CURRENT.set(ctx);
    }

    /** Removes the context from the calling thread's ThreadLocal. */
    public static void uninstall() {
        CURRENT.remove();
    }

    /**
     * Returns the context active on the current thread, or {@code null} if called
     * outside an {@code @AsyncTest} method.
     */
    public static AsyncTestContext get() {
        return CURRENT.get();
    }

    // ---- Internal reporting ----

    /**
     * Delegates to {@link DetectorRegistry#analyzeAll()}.
     * Called by {@link se.deversity.asynctest.runner.ConcurrencyRunner} after the test.
     *
     * @return list of non-empty issue reports; never {@code null}
     */
    public List<String> analyzeAll() {
        return registry.analyzeAll();
    }

    // ---- Public static detector accessors ----

    /**
     * Returns the {@link FalseSharingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFalseSharing = false}
     */
    public static FalseSharingDetector falseSharingDetector() {
        return require("detectFalseSharing", c -> c.falseSharingDetector);
    }

    /**
     * Returns the {@link WakeupDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWakeupIssues = false}
     */
    public static WakeupDetector wakeupDetector() {
        return require("detectWakeupIssues", c -> c.wakeupDetector);
    }

    /**
     * Returns the {@link ConstructorSafetyValidator} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code validateConstructorSafety = false}
     */
    public static ConstructorSafetyValidator constructorSafetyValidator() {
        return require("validateConstructorSafety", c -> c.constructorSafetyValidator);
    }

    /**
     * Returns the {@link ABAProblemDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectABAProblem = false}
     */
    public static ABAProblemDetector abaProblemDetector() {
        return require("detectABAProblem", c -> c.abaProblemDetector);
    }

    /**
     * Returns the {@link LockOrderValidator} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code validateLockOrder = false}
     */
    public static LockOrderValidator lockOrderValidator() {
        return require("validateLockOrder", c -> c.lockOrderValidator);
    }

    /**
     * Returns the {@link SynchronizerMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorSynchronizers = false}
     */
    public static SynchronizerMonitor synchronizerMonitor() {
        return require("monitorSynchronizers", c -> c.synchronizerMonitor);
    }

    /**
     * Returns the {@link ThreadPoolMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorThreadPool = false}
     */
    public static ThreadPoolMonitor threadPoolMonitor() {
        return require("monitorThreadPool", c -> c.threadPoolMonitor);
    }

    /**
     * Returns the {@link MemoryOrderingMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMemoryOrderingViolations = false}
     */
    public static MemoryOrderingMonitor memoryOrderingMonitor() {
        return require("detectMemoryOrderingViolations", c -> c.memoryOrderingMonitor);
    }

    /**
     * Returns the {@link PipelineMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorAsyncPipeline = false}
     */
    public static PipelineMonitor pipelineMonitor() {
        return require("monitorAsyncPipeline", c -> c.pipelineMonitor);
    }

    /**
     * Returns the {@link ReadWriteLockMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorReadWriteLockFairness = false}
     */
    public static ReadWriteLockMonitor readWriteLockMonitor() {
        return require("monitorReadWriteLockFairness", c -> c.readWriteLockMonitor);
    }

    /**
     * Returns the {@link SemaphoreMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorSemaphore = false}
     */
    public static SemaphoreMisuseDetector semaphoreMonitor() {
        return require("monitorSemaphore", c -> c.semaphoreMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureExceptionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureExceptions = false}
     */
    public static CompletableFutureExceptionDetector completableFutureMonitor() {
        return require("detectCompletableFutureExceptions", c -> c.completableFutureExceptionDetector);
    }

    /**
     * Returns the {@link CompletableFutureCompletionLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureCompletionLeaks = false}
     * @since 1.2.0
     */
    public static CompletableFutureCompletionLeakDetector completableFutureCompletionLeakDetector() {
        return require("detectCompletableFutureCompletionLeaks", c -> c.completableFutureCompletionLeakDetector);
    }

    /**
     * Returns the {@link VirtualThreadPinningDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadPinning = false}
     * @since 1.2.0
     */
    public static VirtualThreadPinningDetector virtualThreadPinningDetector() {
        return require("detectVirtualThreadPinning", c -> c.virtualThreadPinningDetector);
    }

    /**
     * Returns the {@link ThreadPoolDeadlockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadPoolDeadlocks = false}
     * @since 1.2.0
     */
    public static ThreadPoolDeadlockDetector threadPoolDeadlockDetector() {
        return require("detectThreadPoolDeadlocks", c -> c.threadPoolDeadlockDetector);
    }

    /**
     * Returns the {@link ConcurrentModificationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentModifications = false}
     */
    public static ConcurrentModificationDetector concurrentModificationMonitor() {
        return require("detectConcurrentModifications", c -> c.concurrentModificationDetector);
    }

    /**
     * Returns the {@link LockLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockLeaks = false}
     */
    public static LockLeakDetector lockLeakMonitor() {
        return require("detectLockLeaks", c -> c.lockLeakDetector);
    }

    /**
     * Returns the {@link SharedRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedRandom = false}
     */
    public static SharedRandomDetector sharedRandomMonitor() {
        return require("detectSharedRandom", c -> c.sharedRandomDetector);
    }

    /**
     * Returns the {@link BlockingQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBlockingQueueIssues = false}
     */
    public static BlockingQueueDetector blockingQueueMonitor() {
        return require("detectBlockingQueueIssues", c -> c.blockingQueueDetector);
    }

    /**
     * Returns the {@link ConditionVariableDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConditionVariableIssues = false}
     */
    public static ConditionVariableDetector conditionMonitor() {
        return require("detectConditionVariableIssues", c -> c.conditionVariableDetector);
    }

    /**
     * Returns the {@link SimpleDateFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSimpleDateFormatIssues = false}
     */
    public static SimpleDateFormatDetector simpleDateFormatMonitor() {
        return require("detectSimpleDateFormatIssues", c -> c.simpleDateFormatDetector);
    }

    /**
     * Returns the {@link ParallelStreamDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectParallelStreamIssues = false}
     */
    public static ParallelStreamDetector parallelStreamMonitor() {
        return require("detectParallelStreamIssues", c -> c.parallelStreamDetector);
    }

    /**
     * Returns the {@link ResourceLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectResourceLeaks = false}
     */
    public static ResourceLeakDetector resourceLeakMonitor() {
        return require("detectResourceLeaks", c -> c.resourceLeakDetector);
    }

    /**
     * Returns the {@link CountDownLatchDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCountDownLatchIssues = false}
     */
    public static CountDownLatchDetector countDownLatchMonitor() {
        return require("detectCountDownLatchIssues", c -> c.countDownLatchDetector);
    }

    /**
     * Returns the {@link CyclicBarrierDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCyclicBarrierIssues = false}
     */
    public static CyclicBarrierDetector cyclicBarrierMonitor() {
        return require("detectCyclicBarrierIssues", c -> c.cyclicBarrierDetector);
    }

    /**
     * Returns the {@link ReentrantLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectReentrantLockIssues = false}
     */
    public static ReentrantLockDetector reentrantLockMonitor() {
        return require("detectReentrantLockIssues", c -> c.reentrantLockDetector);
    }

    /**
     * Returns the {@link VolatileArrayDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVolatileArrayIssues = false}
     */
    public static VolatileArrayDetector volatileArrayMonitor() {
        return require("detectVolatileArrayIssues", c -> c.volatileArrayDetector);
    }

    /**
     * Returns the {@link DoubleCheckedLockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDoubleCheckedLocking = false}
     */
    public static DoubleCheckedLockingDetector doubleCheckedLockingMonitor() {
        return require("detectDoubleCheckedLocking", c -> c.doubleCheckedLockingDetector);
    }

    /**
     * Returns the {@link WaitTimeoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWaitTimeout = false}
     */
    public static WaitTimeoutDetector waitTimeoutMonitor() {
        return require("detectWaitTimeout", c -> c.waitTimeoutDetector);
    }

    /**
     * Returns the {@link LockContentionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockContention = false}
     */
    public static LockContentionDetector lockContentionDetector() {
        return require("detectLockContention", c -> c.lockContentionDetector);
    }

    /**
     * Returns the {@link SynchronizedNonFinalDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedNonFinal = false}
     */
    public static SynchronizedNonFinalDetector synchronizedNonFinalDetector() {
        return require("detectSynchronizedNonFinal", c -> c.synchronizedNonFinalDetector);
    }

    /**
     * Returns the {@link MissedSignalDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMissedSignals = false}
     */
    public static MissedSignalDetector missedSignalDetector() {
        return require("detectMissedSignals", c -> c.missedSignalDetector);
    }

    /**
     * Returns the {@link LazyInitRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLazyInitRace = false}
     */
    public static LazyInitRaceDetector lazyInitRaceDetector() {
        return require("detectLazyInitRace", c -> c.lazyInitRaceDetector);
    }

    /**
     * Returns the {@link PhaserDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPhaserIssues = false}
     */
    public static PhaserDetector phaserMonitor() {
        return require("detectPhaserIssues", c -> c.phaserDetector);
    }

    /**
     * Returns the {@link StampedLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStampedLockIssues = false}
     */
    public static StampedLockDetector stampedLockMonitor() {
        return require("detectStampedLockIssues", c -> c.stampedLockDetector);
    }

    /**
     * Returns the {@link ExchangerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExchangerIssues = false}
     */
    public static ExchangerDetector exchangerMonitor() {
        return require("detectExchangerIssues", c -> c.exchangerDetector);
    }

    /**
     * Returns the {@link ScheduledExecutorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScheduledExecutorIssues = false}
     */
    public static ScheduledExecutorDetector scheduledExecutorMonitor() {
        return require("detectScheduledExecutorIssues", c -> c.scheduledExecutorDetector);
    }

    /**
     * Returns the {@link ForkJoinPoolDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinPoolIssues = false}
     */
    public static ForkJoinPoolDetector forkJoinPoolMonitor() {
        return require("detectForkJoinPoolIssues", c -> c.forkJoinPoolDetector);
    }

    /**
     * Returns the {@link ThreadFactoryDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadFactoryIssues = false}
     */
    public static ThreadFactoryDetector threadFactoryMonitor() {
        return require("detectThreadFactoryIssues", c -> c.threadFactoryDetector);
    }

    // ---- Phase 4: Infrastructure & Resource Management ----

    /**
     * Returns the {@link ThreadLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLeaks = false}
     */
    public static ThreadLeakDetector threadLeakDetector() {
        return require("detectThreadLeaks", c -> c.threadLeakDetector);
    }

    /**
     * Returns the {@link SleepInLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSleepInLock = false}
     */
    public static SleepInLockDetector sleepInLockDetector() {
        return require("detectSleepInLock", c -> c.sleepInLockDetector);
    }

    /**
     * Returns the {@link UnboundedQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectUnboundedQueue = false}
     */
    public static UnboundedQueueDetector unboundedQueueDetector() {
        return require("detectUnboundedQueue", c -> c.unboundedQueueDetector);
    }

    /**
     * Returns the {@link ThreadStarvationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadStarvation = false}
     */
    public static ThreadStarvationDetector threadStarvationDetector() {
        return require("detectThreadStarvation", c -> c.threadStarvationDetector);
    }

    // ---- Phase 5: Thread-Safety of Common Types ----

    /**
     * Returns the {@link CalendarDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCalendarIssues = false}
     */
    public static CalendarDetector calendarMonitor() {
        return require("detectCalendarIssues", c -> c.calendarDetector);
    }

    /**
     * Returns the {@link SharedCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedCollections = false}
     */
    public static SharedCollectionDetector sharedCollectionMonitor() {
        return require("detectSharedCollections", c -> c.sharedCollectionDetector);
    }

    /**
     * Returns the {@link TimerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectTimerIssues = false}
     */
    public static TimerDetector timerMonitor() {
        return require("detectTimerIssues", c -> c.timerDetector);
    }

    /**
     * Returns the {@link CopyOnWriteCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCopyOnWriteCollectionIssues = false}
     */
    public static CopyOnWriteCollectionDetector copyOnWriteMonitor() {
        return require("detectCopyOnWriteCollectionIssues", c -> c.copyOnWriteCollectionDetector);
    }

    /**
     * Returns the {@link StringBuilderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStringBuilderIssues = false}
     */
    public static StringBuilderDetector stringBuilderMonitor() {
        return require("detectStringBuilderIssues", c -> c.stringBuilderDetector);
    }

    // ---- Phase 6: Virtual Thread Concurrency (Java 21+) ----

    /**
     * Returns the {@link StructuredConcurrencyMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStructuredConcurrencyIssues = false}
     */
    public static StructuredConcurrencyMisuseDetector structuredConcurrencyMisuseDetector() {
        return require("detectStructuredConcurrencyIssues", c -> c.structuredConcurrencyMisuseDetector);
    }

    /**
     * Returns the {@link VirtualThreadContextLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadContextLeaks = false}
     */
    public static VirtualThreadContextLeakDetector virtualThreadContextLeakDetector() {
        return require("detectVirtualThreadContextLeaks", c -> c.virtualThreadContextLeakDetector);
    }

    /**
     * Returns the {@link ScopedValueMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScopedValueMisuse = false}
     */
    public static ScopedValueMisuseDetector scopedValueMisuseDetector() {
        return require("detectScopedValueMisuse", c -> c.scopedValueMisuseDetector);
    }

    /**
     * Returns the {@link VirtualThreadCpuBoundTaskDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadCpuBoundTasks = false}
     * @since 0.7.0
     */
    public static VirtualThreadCpuBoundTaskDetector virtualThreadCpuBoundTaskDetector() {
        return require("detectVirtualThreadCpuBoundTasks", c -> c.virtualThreadCpuBoundTaskDetector);
    }

    /**
     * Returns the {@link VirtualThreadCarrierExhaustionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadCarrierExhaustion = false}
     * @since 0.7.0
     */
    public static VirtualThreadCarrierExhaustionDetector virtualThreadCarrierExhaustionDetector() {
        return require("detectVirtualThreadCarrierExhaustion", c -> c.virtualThreadCarrierExhaustionDetector);
    }

    // ---- Phase 7: High-Level Concurrency Patterns ----

    /**
     * Returns the {@link HttpClientConcurrencyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectHttpClientIssues = false}
     * @since 0.7.0
     */
    public static HttpClientConcurrencyDetector httpClientDetector() {
        return require("detectHttpClientIssues", c -> c.httpClientConcurrencyDetector);
    }

    /**
     * Returns the {@link StreamClosingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStreamClosing = false}
     * @since 0.7.0
     */
    public static StreamClosingDetector streamClosingDetector() {
        return require("detectStreamClosing", c -> c.streamClosingDetector);
    }

    /**
     * Returns the {@link CacheConcurrencyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCacheConcurrency = false}
     * @since 0.7.0
     */
    public static CacheConcurrencyDetector cacheConcurrencyDetector() {
        return require("detectCacheConcurrency", c -> c.cacheConcurrencyDetector);
    }

    /**
     * Returns the {@link CompletableFutureChainDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureChainIssues = false}
     * @since 0.7.0
     */
    public static CompletableFutureChainDetector cfChainDetector() {
        return require("detectCompletableFutureChainIssues", c -> c.completableFutureChainDetector);
    }

    // ---- Phase 8: Lifecycle & Structural Correctness ----

    /**
     * Returns the {@link ExecutorShutdownDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExecutorShutdown = false}
     */
    public static ExecutorShutdownDetector executorShutdownMonitor() {
        return require("detectExecutorShutdown", c -> c.executorShutdownDetector);
    }

    /**
     * Returns the {@link MutableMapKeyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMutableMapKeys = false}
     */
    public static MutableMapKeyDetector mutableMapKeyMonitor() {
        return require("detectMutableMapKeys", c -> c.mutableMapKeyDetector);
    }

    /**
     * Returns the {@link NestedMonitorLockoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectNestedMonitorLockout = false}
     */
    public static NestedMonitorLockoutDetector nestedMonitorLockoutMonitor() {
        return require("detectNestedMonitorLockout", c -> c.nestedMonitorLockoutDetector);
    }

    /**
     * Returns the {@link LockDowngradeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockDowngrade = false}
     */
    public static LockDowngradeDetector lockDowngradeMonitor() {
        return require("detectLockDowngrade", c -> c.lockDowngradeDetector);
    }

    /**
     * Returns the {@link InheritableThreadLocalMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectInheritableThreadLocalMisuse = false}
     */
    public static InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseMonitor() {
        return require("detectInheritableThreadLocalMisuse", c -> c.inheritableThreadLocalMisuseDetector);
    }

    /**
     * Returns the {@link UncommittedChangesDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectUncommittedChanges = false}
     */
    public static UncommittedChangesDetector uncommittedChangesMonitor() {
        return require("detectUncommittedChanges", c -> c.uncommittedChangesDetector);
    }

    /**
     * Returns the {@link ThreadLocalContaminationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalContamination = false}
     */
    public static ThreadLocalContaminationDetector threadLocalContaminationMonitor() {
        return require("detectThreadLocalContamination", c -> c.threadLocalContaminationDetector);
    }

    /**
     * Returns the {@link AtomicNonAtomicUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectAtomicNonAtomicUpdates = false}
     */
    public static AtomicNonAtomicUpdateDetector atomicNonAtomicUpdateMonitor() {
        return require("detectAtomicNonAtomicUpdates", c -> c.atomicNonAtomicUpdateDetector);
    }

    /**
     * Returns the {@link SynchronizedCollectionIterationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedCollectionIteration = false}
     */
    public static SynchronizedCollectionIterationDetector synchronizedCollectionIterationMonitor() {
        return require("detectSynchronizedCollectionIteration", c -> c.synchronizedCollectionIterationDetector);
    }

    /**
     * Returns the {@link SharedFormatterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedFormatter = false}
     */
    public static SharedFormatterDetector sharedFormatterMonitor() {
        return require("detectSharedFormatter", c -> c.sharedFormatterDetector);
    }

    /**
     * Returns the {@link ConcurrentMapComputeRecursionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentMapComputeRecursion = false}
     */
    public static ConcurrentMapComputeRecursionDetector concurrentMapComputeRecursionMonitor() {
        return require("detectConcurrentMapComputeRecursion", c -> c.concurrentMapComputeRecursionDetector);
    }

    /**
     * Returns the {@link SynchronizedOnLiteralDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedOnLiteral = false}
     */
    public static SynchronizedOnLiteralDetector synchronizedOnLiteralMonitor() {
        return require("detectSynchronizedOnLiteral", c -> c.synchronizedOnLiteralDetector);
    }

    /**
     * Returns the {@link PublicLockExposureDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPublicLockExposure = false}
     */
    public static PublicLockExposureDetector publicLockExposureMonitor() {
        return require("detectPublicLockExposure", c -> c.publicLockExposureDetector);
    }

    /**
     * Returns the {@link ForkJoinTaskBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinTaskBlocking = false}
     */
    public static ForkJoinTaskBlockingDetector forkJoinTaskBlockingMonitor() {
        return require("detectForkJoinTaskBlocking", c -> c.forkJoinTaskBlockingDetector);
    }

    /**
     * Returns the {@link OptimisticReadValidationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectOptimisticReadValidation = false}
     */
    public static OptimisticReadValidationDetector optimisticReadValidationMonitor() {
        return require("detectOptimisticReadValidation", c -> c.optimisticReadValidationDetector);
    }

    /**
     * Returns the {@link CompletableFutureCommonPoolBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCFCommonPoolBlocking = false}
     */
    public static CompletableFutureCommonPoolBlockingDetector cfCommonPoolBlockingMonitor() {
        return require("detectCFCommonPoolBlocking", c -> c.cfCommonPoolBlockingDetector);
    }

    // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----

    /**
     * Returns the {@link SharedMatcherDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedMatcher = false}
     * @since 0.9.0
     */
    public static SharedMatcherDetector sharedMatcherDetector() {
        return require("detectSharedMatcher", c -> c.sharedMatcherDetector);
    }

    /**
     * Returns the {@link SharedDecimalFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedDecimalFormat = false}
     * @since 0.9.0
     */
    public static SharedDecimalFormatDetector sharedDecimalFormatDetector() {
        return require("detectSharedDecimalFormat", c -> c.sharedDecimalFormatDetector);
    }

    /**
     * Returns the {@link WeakReferenceRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWeakReferenceRace = false}
     * @since 0.9.0
     */
    public static WeakReferenceRaceDetector weakReferenceRaceDetector() {
        return require("detectWeakReferenceRace", c -> c.weakReferenceRaceDetector);
    }

    /**
     * Returns the {@link StatefulLambdaDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStatefulLambda = false}
     * @since 0.9.0
     */
    public static StatefulLambdaDetector statefulLambdaDetector() {
        return require("detectStatefulLambda", c -> c.statefulLambdaDetector);
    }

    /**
     * Returns the {@link SharedMessageDigestDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedMessageDigest = false}
     * @since 0.9.0
     */
    public static SharedMessageDigestDetector sharedMessageDigestDetector() {
        return require("detectSharedMessageDigest", c -> c.sharedMessageDigestDetector);
    }

    // ---- Phase 12: Operational & Hygiene Concurrency Issues ----

    /**
     * Returns the {@link InterruptSwallowingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectInterruptSwallowing = false}
     * @since 0.10.0
     */
    public static InterruptSwallowingDetector interruptSwallowingDetector() {
        return require("detectInterruptSwallowing", c -> c.interruptSwallowingDetector);
    }

    /**
     * Returns the {@link MdcContextLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMdcContextLeak = false}
     * @since 0.10.0
     */
    public static MdcContextLeakDetector mdcContextLeakDetector() {
        return require("detectMdcContextLeak", c -> c.mdcContextLeakDetector);
    }

    /**
     * Returns the {@link SystemPropertyMutationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSystemPropertyMutation = false}
     * @since 0.10.0
     */
    public static SystemPropertyMutationDetector systemPropertyMutationDetector() {
        return require("detectSystemPropertyMutation", c -> c.systemPropertyMutationDetector);
    }

    /**
     * Returns the {@link FutureIgnoredDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFutureIgnored = false}
     * @since 0.10.0
     */
    public static FutureIgnoredDetector futureIgnoredDetector() {
        return require("detectFutureIgnored", c -> c.futureIgnoredDetector);
    }

    /**
     * Returns the {@link ExplicitGcDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExplicitGc = false}
     * @since 0.10.0
     */
    public static ExplicitGcDetector explicitGcDetector() {
        return require("detectExplicitGc", c -> c.explicitGcDetector);
    }

    /**
     * Returns the {@link DeprecatedThreadApiDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDeprecatedThreadApi = false}
     * @since 0.10.0
     */
    public static DeprecatedThreadApiDetector deprecatedThreadApiDetector() {
        return require("detectDeprecatedThreadApi", c -> c.deprecatedThreadApiDetector);
    }

    /**
     * Returns the {@link SharedXmlParserDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedXmlParser = false}
     * @since 0.10.0
     */
    public static SharedXmlParserDetector sharedXmlParserDetector() {
        return require("detectSharedXmlParser", c -> c.sharedXmlParserDetector);
    }

    /**
     * Returns the {@link BoxedPrimitiveLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBoxedPrimitiveLock = false}
     * @since 0.10.0
     */
    public static BoxedPrimitiveLockDetector boxedPrimitiveLockDetector() {
        return require("detectBoxedPrimitiveLock", c -> c.boxedPrimitiveLockDetector);
    }

    /**
     * Returns the {@link SharedTimeZoneDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedTimeZone = false}
     * @since 0.10.0
     */
    public static SharedTimeZoneDetector sharedTimeZoneDetector() {
        return require("detectSharedTimeZone", c -> c.sharedTimeZoneDetector);
    }

    /**
     * Returns the {@link UncaughtExceptionHandlerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectUncaughtExceptionHandler = false}
     * @since 0.10.0
     */
    public static UncaughtExceptionHandlerDetector uncaughtExceptionHandlerDetector() {
        return require("detectUncaughtExceptionHandler", c -> c.uncaughtExceptionHandlerDetector);
    }

    // ---- Helper ----

    private static <T> T require(String flag, Function<AsyncTestContext, T> fn) {
        AsyncTestContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                "AsyncTestContext is not active — this accessor can only be called inside an @AsyncTest method.");
        }
        T val = fn.apply(ctx);
        if (val == null) {
            throw new IllegalStateException(
                "Detector not active: set " + flag + " = true on @AsyncTest to enable it," +
                " or use detectAll = true to enable every detector at once.");
        }
        return val;
    }
}
