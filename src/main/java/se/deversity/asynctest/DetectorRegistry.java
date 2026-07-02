package se.deversity.asynctest;

import se.deversity.asynctest.diagnostics.ABAProblemDetector;
import se.deversity.asynctest.diagnostics.AtomicNonAtomicUpdateDetector;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.BoxedPrimitiveLockDetector;
import se.deversity.asynctest.diagnostics.BusyWaitDetector;
import se.deversity.asynctest.diagnostics.CacheConcurrencyDetector;
import se.deversity.asynctest.diagnostics.CalendarDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureChainDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCommonPoolBlockingDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCompletionLeakDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureExceptionDetector;
import se.deversity.asynctest.diagnostics.ConcurrentMapComputeRecursionDetector;
import se.deversity.asynctest.diagnostics.ConcurrentModificationDetector;
import se.deversity.asynctest.diagnostics.ConditionVariableDetector;
import se.deversity.asynctest.diagnostics.ConstructorSafetyValidator;
import se.deversity.asynctest.diagnostics.CopyOnWriteCollectionDetector;
import se.deversity.asynctest.diagnostics.CountDownLatchDetector;
import se.deversity.asynctest.diagnostics.CyclicBarrierDetector;
import se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector;
import se.deversity.asynctest.diagnostics.DeadlockDetector;
import se.deversity.asynctest.diagnostics.DeprecatedThreadApiDetector;
import se.deversity.asynctest.diagnostics.DoubleCheckedLockingDetector;
import se.deversity.asynctest.diagnostics.ExchangerDetector;
import se.deversity.asynctest.diagnostics.ExecutorShutdownDetector;
import se.deversity.asynctest.diagnostics.ExplicitGcDetector;
import se.deversity.asynctest.diagnostics.FalseSharingDetector;
import se.deversity.asynctest.diagnostics.ForkJoinPoolDetector;
import se.deversity.asynctest.diagnostics.ForkJoinTaskBlockingDetector;
import se.deversity.asynctest.diagnostics.FutureIgnoredDetector;
import se.deversity.asynctest.diagnostics.GathererConcurrencyMisuseDetector;
import se.deversity.asynctest.diagnostics.HttpClientConcurrencyDetector;
import se.deversity.asynctest.diagnostics.InheritableThreadLocalMisuseDetector;
import se.deversity.asynctest.diagnostics.InterruptMonitor;
import se.deversity.asynctest.diagnostics.InterruptSwallowingDetector;
import se.deversity.asynctest.diagnostics.JdbcConnectionSharedDetector;
import se.deversity.asynctest.diagnostics.LazyInitRaceDetector;
import se.deversity.asynctest.diagnostics.LivelockDetector;
import se.deversity.asynctest.diagnostics.LockContentionDetector;
import se.deversity.asynctest.diagnostics.LockDowngradeDetector;
import se.deversity.asynctest.diagnostics.LockLeakDetector;
import se.deversity.asynctest.diagnostics.LockOrderValidator;
import se.deversity.asynctest.diagnostics.MdcContextLeakDetector;
import se.deversity.asynctest.diagnostics.MemoryOrderingMonitor;
import se.deversity.asynctest.diagnostics.MissedSignalDetector;
import se.deversity.asynctest.diagnostics.MutableMapKeyDetector;
import se.deversity.asynctest.diagnostics.NestedMonitorLockoutDetector;
import se.deversity.asynctest.diagnostics.NonAtomicConcurrentMapUpdateDetector;
import se.deversity.asynctest.diagnostics.NotifyWithoutMonitorDetector;
import se.deversity.asynctest.diagnostics.OptimisticReadValidationDetector;
import se.deversity.asynctest.diagnostics.ParallelStreamDetector;
import se.deversity.asynctest.diagnostics.PhaserDetector;
import se.deversity.asynctest.diagnostics.PipelineMonitor;
import se.deversity.asynctest.diagnostics.PublicLockExposureDetector;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.diagnostics.ReadWriteLockMonitor;
import se.deversity.asynctest.diagnostics.ReentrantLockDetector;
import se.deversity.asynctest.diagnostics.ResourceLeakDetector;
import se.deversity.asynctest.diagnostics.ScheduledExecutorDetector;
import se.deversity.asynctest.diagnostics.ScopedValueMisuseDetector;
import se.deversity.asynctest.diagnostics.SemaphoreMisuseDetector;
import se.deversity.asynctest.diagnostics.SharedCollectionDetector;
import se.deversity.asynctest.diagnostics.SharedDecimalFormatDetector;
import se.deversity.asynctest.diagnostics.SharedDeflaterDetector;
import se.deversity.asynctest.diagnostics.SharedFormatterDetector;
import se.deversity.asynctest.diagnostics.SharedMatcherDetector;
import se.deversity.asynctest.diagnostics.SharedMessageDigestDetector;
import se.deversity.asynctest.diagnostics.SharedRandomDetector;
import se.deversity.asynctest.diagnostics.SharedSecureRandomDetector;
import se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector;
import se.deversity.asynctest.diagnostics.SharedTimeZoneDetector;
import se.deversity.asynctest.diagnostics.SharedXmlParserDetector;
import se.deversity.asynctest.diagnostics.SimpleDateFormatDetector;
import se.deversity.asynctest.diagnostics.SleepInLockDetector;
import se.deversity.asynctest.diagnostics.StableValueMisuseDetector;
import se.deversity.asynctest.diagnostics.StampedLockDetector;
import se.deversity.asynctest.diagnostics.StatefulLambdaDetector;
import se.deversity.asynctest.diagnostics.StreamClosingDetector;
import se.deversity.asynctest.diagnostics.StringBuilderDetector;
import se.deversity.asynctest.diagnostics.StructuredConcurrencyMisuseDetector;
import se.deversity.asynctest.diagnostics.StructuredTaskScopeMisuseDetector;
import se.deversity.asynctest.diagnostics.SynchronizedCollectionIterationDetector;
import se.deversity.asynctest.diagnostics.SynchronizedNonFinalDetector;
import se.deversity.asynctest.diagnostics.SynchronizedOnLiteralDetector;
import se.deversity.asynctest.diagnostics.SynchronizerMonitor;
import se.deversity.asynctest.diagnostics.SystemPropertyMutationDetector;
import se.deversity.asynctest.diagnostics.ThisEscapeDetector;
import se.deversity.asynctest.diagnostics.ThreadFactoryDetector;
import se.deversity.asynctest.diagnostics.ThreadLeakDetector;
import se.deversity.asynctest.diagnostics.ThreadLocalContaminationDetector;
import se.deversity.asynctest.diagnostics.ThreadLocalMonitor;
import se.deversity.asynctest.diagnostics.ThreadLocalRandomMisuseDetector;
import se.deversity.asynctest.diagnostics.ThreadPoolDeadlockDetector;
import se.deversity.asynctest.diagnostics.ThreadPoolMonitor;
import se.deversity.asynctest.diagnostics.ThreadStarvationDetector;
import se.deversity.asynctest.diagnostics.TimerDetector;
import se.deversity.asynctest.diagnostics.UnboundedQueueDetector;
import se.deversity.asynctest.diagnostics.UncaughtExceptionHandlerDetector;
import se.deversity.asynctest.diagnostics.UncommittedChangesDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadCarrierExhaustionDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadContextLeakDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadCpuBoundTaskDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadPinningDetector;
import se.deversity.asynctest.diagnostics.VisibilityMonitor;
import se.deversity.asynctest.diagnostics.VolatileArrayDetector;
import se.deversity.asynctest.diagnostics.WaitTimeoutDetector;
import se.deversity.asynctest.diagnostics.WakeupDetector;
import se.deversity.asynctest.diagnostics.WeakHashMapSharedDetector;
import se.deversity.asynctest.diagnostics.WeakReferenceRaceDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector;
import se.deversity.asynctest.diagnostics.SpuriousWakeupDetector;
import se.deversity.asynctest.diagnostics.LockUpgradeDeadlockDetector;
import se.deversity.asynctest.diagnostics.TryLockMisuseDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector;
import se.deversity.asynctest.diagnostics.SharedByteBufferDetector;
import se.deversity.asynctest.diagnostics.SharedCharsetCoderDetector;
import se.deversity.asynctest.diagnostics.SharedChecksumDetector;
import se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector;
import se.deversity.asynctest.diagnostics.SharedIteratorDetector;
import se.deversity.asynctest.diagnostics.HighContentionAtomicDetector;
import se.deversity.asynctest.diagnostics.SharedJsonMapperReconfigDetector;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Holds all Phase 2 detector instances for a single test run and orchestrates
 * their post-run analysis.
 *
 * <p>This class was extracted from {@link AsyncTestContext} to separate two
 * concerns: detector lifecycle (this class) from ThreadLocal context management
 * ({@link AsyncTestContext}).
 *
 * <p>A {@code DetectorRegistry} is created once per test method execution by
 * {@link se.deversity.asynctest.runner.ConcurrencyRunner} and shared across all
 * worker threads via the {@link AsyncTestContext} ThreadLocal.
 *
 * <p>All detector fields are package-private so that {@link AsyncTestContext}
 * static accessors can read them directly without reflection overhead.
 */
@AIContext(
    focus = "Each new detector requires exactly three steps in this class: (1) a final field declaration, (2) conditional construction in the constructor keyed on the config flag, (3) an analyzeAll() call in the correct phase block. All three steps must be added together.",
    avoids = "partial patterns — a field without construction or analysis silently skips detection"
)
@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED, note = "Guards conditional access to internal detector initialization and phase blocks.")
final class DetectorRegistry {

    // ---- Phase 1 ----
    final DeadlockDetector  deadlockDetector;
    final VisibilityMonitor visibilityMonitor;
    final LivelockDetector  livelockDetector;

    // ---- Phase 2: Core ----
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

    // ---- Phase 2: Additional monitors ----
    final SemaphoreMisuseDetector              semaphoreMisuseDetector;
    final CompletableFutureExceptionDetector   completableFutureExceptionDetector;
    final CompletableFutureCompletionLeakDetector completableFutureCompletionLeakDetector;
    final VirtualThreadPinningDetector         virtualThreadPinningDetector;
    final ThreadPoolDeadlockDetector           threadPoolDeadlockDetector;
    final ConcurrentModificationDetector       concurrentModificationDetector;
    final LockLeakDetector                     lockLeakDetector;
    final SharedRandomDetector                 sharedRandomDetector;
    final BlockingQueueDetector                blockingQueueDetector;
    final ConditionVariableDetector            conditionVariableDetector;
    final SimpleDateFormatDetector             simpleDateFormatDetector;
    final ParallelStreamDetector               parallelStreamDetector;
    final ResourceLeakDetector                 resourceLeakDetector;

    // ---- Phase 2: Additional concurrency ----
    final CountDownLatchDetector           countDownLatchDetector;
    final CyclicBarrierDetector            cyclicBarrierDetector;
    final ReentrantLockDetector            reentrantLockDetector;
    final VolatileArrayDetector            volatileArrayDetector;
    final DoubleCheckedLockingDetector     doubleCheckedLockingDetector;
    final WaitTimeoutDetector              waitTimeoutDetector;
    final LockContentionDetector           lockContentionDetector;
    final SynchronizedNonFinalDetector     synchronizedNonFinalDetector;
    final MissedSignalDetector             missedSignalDetector;
    final LazyInitRaceDetector             lazyInitRaceDetector;

    // ---- Phase 2: Advanced concurrency utilities ----
    final PhaserDetector             phaserDetector;
    final StampedLockDetector        stampedLockDetector;
    final ExchangerDetector          exchangerDetector;
    final ScheduledExecutorDetector  scheduledExecutorDetector;
    final ForkJoinPoolDetector       forkJoinPoolDetector;
    final ThreadFactoryDetector      threadFactoryDetector;

    // ---- Phase 3 ----
    final RaceConditionDetector raceConditionDetector;
    final ThreadLocalMonitor    threadLocalMonitor;
    final BusyWaitDetector      busyWaitDetector;
    final AtomicityValidator    atomicityValidator;
    final InterruptMonitor      interruptMonitor;

    // ---- Phase 4: Infrastructure & Resource Management ----
    final ThreadLeakDetector         threadLeakDetector;
    final SleepInLockDetector        sleepInLockDetector;
    final UnboundedQueueDetector     unboundedQueueDetector;
    final ThreadStarvationDetector   threadStarvationDetector;

    // ---- Phase 5: Thread-Safety of Common Types ----
    final CalendarDetector              calendarDetector;
    final SharedCollectionDetector      sharedCollectionDetector;
    final TimerDetector                 timerDetector;
    final CopyOnWriteCollectionDetector copyOnWriteCollectionDetector;
    final StringBuilderDetector         stringBuilderDetector;

    // ---- Phase 6: Virtual Thread Concurrency (Java 21+) ----
    final StructuredConcurrencyMisuseDetector  structuredConcurrencyMisuseDetector;
    final VirtualThreadContextLeakDetector     virtualThreadContextLeakDetector;
    final ScopedValueMisuseDetector            scopedValueMisuseDetector;
    final VirtualThreadCpuBoundTaskDetector    virtualThreadCpuBoundTaskDetector;
    final VirtualThreadCarrierExhaustionDetector virtualThreadCarrierExhaustionDetector;

    // ---- Phase 7: High-Level Concurrency Patterns ----
    final HttpClientConcurrencyDetector       httpClientConcurrencyDetector;
    final StreamClosingDetector               streamClosingDetector;
    final CacheConcurrencyDetector            cacheConcurrencyDetector;
    final CompletableFutureChainDetector      completableFutureChainDetector;

    // ---- Phase 8: Lifecycle & Structural Correctness ----
    final ExecutorShutdownDetector            executorShutdownDetector;
    final MutableMapKeyDetector               mutableMapKeyDetector;
    final NestedMonitorLockoutDetector        nestedMonitorLockoutDetector;
    final LockDowngradeDetector               lockDowngradeDetector;
    final InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseDetector;
    final UncommittedChangesDetector          uncommittedChangesDetector;

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

    // ---- Phase 13: Additional concurrency-bug categories (1.0.0+) ----
    final DaemonThreadHygieneDetector       daemonThreadHygieneDetector;
    final NotifyWithoutMonitorDetector      notifyWithoutMonitorDetector;
    final SharedSecureRandomDetector        sharedSecureRandomDetector;
    final WeakHashMapSharedDetector         weakHashMapSharedDetector;
    final JdbcConnectionSharedDetector      jdbcConnectionSharedDetector;

    // ---- Phase 14: Additional thread-unsafe primitives & publication hazards (1.7.0+) ----
    final SharedStatefulCryptoDetector          sharedStatefulCryptoDetector;
    final NonAtomicConcurrentMapUpdateDetector  nonAtomicConcurrentMapUpdateDetector;
    final SharedDeflaterDetector                sharedDeflaterDetector;
    final ThisEscapeDetector                    thisEscapeDetector;
    final ThreadLocalRandomMisuseDetector       threadLocalRandomMisuseDetector;

    // ---- Phase 15: Asynchronous flow & lock-usage hazards (1.8.0+) ----
    final CompletableFutureObtrudeDetector          completableFutureObtrudeDetector;
    final SpuriousWakeupDetector                    spuriousWakeupHazardDetector;
    final LockUpgradeDeadlockDetector               lockUpgradeDeadlockDetector;
    final TryLockMisuseDetector                     tryLockMisuseDetector;
    final CompletableFutureBlockingCallbackDetector cfBlockingCallbackDetector;

    // ---- Phase 16: JDK 25/26 preview-era concurrency detectors ----
    final StableValueMisuseDetector             stableValueMisuseDetector;
    final StructuredTaskScopeMisuseDetector     structuredTaskScopeMisuseDetector;
    final GathererConcurrencyMisuseDetector     gathererConcurrencyMisuseDetector;

    // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----
    final SharedByteBufferDetector              sharedByteBufferDetector;
    final SharedCharsetCoderDetector            sharedCharsetCoderDetector;
    final SharedChecksumDetector                sharedChecksumDetector;
    final FileChannelPositionRaceDetector       fileChannelPositionRaceDetector;
    final SharedIteratorDetector                sharedIteratorDetector;
    final HighContentionAtomicDetector          highContentionAtomicDetector;
    final SharedJsonMapperReconfigDetector      sharedJsonMapperReconfigDetector;

    /**
     * Instantiates detectors based on the enabled flags in {@code cfg}.
     * Detectors whose flag is {@code false} are set to {@code null} and incur
     * zero overhead during the test run.
     */
    DetectorRegistry(AsyncTestConfig cfg) {
        deadlockDetector           = cfg.detectDeadlocks                ? new DeadlockDetector()           : null;
        visibilityMonitor          = cfg.detectVisibility               ? new VisibilityMonitor()           : null;
        livelockDetector           = cfg.detectLivelocks                ? new LivelockDetector()            : null;
        falseSharingDetector       = cfg.detectFalseSharing             ? new FalseSharingDetector()       : null;
        wakeupDetector             = cfg.detectWakeupIssues             ? new WakeupDetector()             : null;
        constructorSafetyValidator = cfg.validateConstructorSafety      ? new ConstructorSafetyValidator() : null;
        abaProblemDetector         = cfg.detectABAProblem               ? new ABAProblemDetector()         : null;
        lockOrderValidator         = cfg.validateLockOrder              ? new LockOrderValidator()         : null;
        synchronizerMonitor        = cfg.monitorSynchronizers           ? new SynchronizerMonitor()        : null;
        threadPoolMonitor          = cfg.monitorThreadPool              ? new ThreadPoolMonitor()          : null;
        memoryOrderingMonitor      = cfg.detectMemoryOrderingViolations ? new MemoryOrderingMonitor()      : null;
        pipelineMonitor            = cfg.monitorAsyncPipeline           ? new PipelineMonitor()            : null;
        readWriteLockMonitor       = cfg.monitorReadWriteLockFairness   ? new ReadWriteLockMonitor()       : null;
        semaphoreMisuseDetector    = cfg.monitorSemaphore               ? new SemaphoreMisuseDetector()    : null;
        completableFutureExceptionDetector = cfg.detectCompletableFutureExceptions
                ? new CompletableFutureExceptionDetector() : null;
        completableFutureCompletionLeakDetector = cfg.detectCompletableFutureCompletionLeaks
                ? new CompletableFutureCompletionLeakDetector() : null;
        virtualThreadPinningDetector = cfg.detectVirtualThreadPinning
                ? new VirtualThreadPinningDetector() : null;
        threadPoolDeadlockDetector = cfg.detectThreadPoolDeadlocks
                ? new ThreadPoolDeadlockDetector() : null;
        concurrentModificationDetector = cfg.detectConcurrentModifications
                ? new ConcurrentModificationDetector() : null;
        lockLeakDetector           = cfg.detectLockLeaks                ? new LockLeakDetector()           : null;
        sharedRandomDetector       = cfg.detectSharedRandom             ? new SharedRandomDetector()       : null;
        blockingQueueDetector      = cfg.detectBlockingQueueIssues      ? new BlockingQueueDetector()      : null;
        conditionVariableDetector  = cfg.detectConditionVariableIssues  ? new ConditionVariableDetector()  : null;
        simpleDateFormatDetector   = cfg.detectSimpleDateFormatIssues   ? new SimpleDateFormatDetector()   : null;
        parallelStreamDetector     = cfg.detectParallelStreamIssues     ? new ParallelStreamDetector()     : null;
        resourceLeakDetector       = cfg.detectResourceLeaks            ? new ResourceLeakDetector()       : null;
        countDownLatchDetector     = cfg.detectCountDownLatchIssues     ? new CountDownLatchDetector()     : null;
        cyclicBarrierDetector      = cfg.detectCyclicBarrierIssues      ? new CyclicBarrierDetector()      : null;
        reentrantLockDetector      = cfg.detectReentrantLockIssues      ? new ReentrantLockDetector()      : null;
        volatileArrayDetector      = cfg.detectVolatileArrayIssues      ? new VolatileArrayDetector()      : null;
        doubleCheckedLockingDetector = cfg.detectDoubleCheckedLocking   ? new DoubleCheckedLockingDetector() : null;
        waitTimeoutDetector        = cfg.detectWaitTimeout              ? new WaitTimeoutDetector()        : null;
        lockContentionDetector     = cfg.detectLockContention           ? new LockContentionDetector()     : null;
        synchronizedNonFinalDetector = cfg.detectSynchronizedNonFinal   ? new SynchronizedNonFinalDetector() : null;
        missedSignalDetector       = cfg.detectMissedSignals            ? new MissedSignalDetector()       : null;
        lazyInitRaceDetector       = cfg.detectLazyInitRace             ? new LazyInitRaceDetector()       : null;
        phaserDetector             = cfg.detectPhaserIssues             ? new PhaserDetector()             : null;
        stampedLockDetector        = cfg.detectStampedLockIssues        ? new StampedLockDetector()        : null;
        exchangerDetector          = cfg.detectExchangerIssues          ? new ExchangerDetector()          : null;
        scheduledExecutorDetector  = cfg.detectScheduledExecutorIssues  ? new ScheduledExecutorDetector()  : null;
        forkJoinPoolDetector       = cfg.detectForkJoinPoolIssues       ? new ForkJoinPoolDetector()       : null;
        threadFactoryDetector      = cfg.detectThreadFactoryIssues      ? new ThreadFactoryDetector()      : null;
        raceConditionDetector      = cfg.detectRaceConditions           ? new RaceConditionDetector()      : null;
        threadLocalMonitor         = cfg.detectThreadLocalLeaks         ? new ThreadLocalMonitor()          : null;
        busyWaitDetector           = cfg.detectBusyWaiting              ? new BusyWaitDetector()            : null;
        atomicityValidator         = cfg.detectAtomicityViolations      ? new AtomicityValidator()          : null;
        interruptMonitor           = cfg.detectInterruptMishandling     ? new InterruptMonitor()            : null;
        threadLeakDetector         = cfg.detectThreadLeaks              ? new ThreadLeakDetector()         : null;
        sleepInLockDetector        = cfg.detectSleepInLock              ? new SleepInLockDetector()        : null;
        unboundedQueueDetector     = cfg.detectUnboundedQueue           ? new UnboundedQueueDetector()     : null;
        threadStarvationDetector   = cfg.detectThreadStarvation         ? new ThreadStarvationDetector()   : null;
        calendarDetector           = cfg.detectCalendarIssues           ? new CalendarDetector()           : null;
        sharedCollectionDetector   = cfg.detectSharedCollections        ? new SharedCollectionDetector()   : null;
        timerDetector              = cfg.detectTimerIssues              ? new TimerDetector()              : null;
        copyOnWriteCollectionDetector = cfg.detectCopyOnWriteCollectionIssues
                ? new CopyOnWriteCollectionDetector() : null;
        stringBuilderDetector      = cfg.detectStringBuilderIssues               ? new StringBuilderDetector()               : null;
        structuredConcurrencyMisuseDetector = cfg.detectStructuredConcurrencyIssues
                ? new StructuredConcurrencyMisuseDetector() : null;
        virtualThreadContextLeakDetector = cfg.detectVirtualThreadContextLeaks
                ? new VirtualThreadContextLeakDetector() : null;
        scopedValueMisuseDetector = cfg.detectScopedValueMisuse
                ? new ScopedValueMisuseDetector() : null;
        virtualThreadCpuBoundTaskDetector = cfg.detectVirtualThreadCpuBoundTasks
                ? new VirtualThreadCpuBoundTaskDetector() : null;
        virtualThreadCarrierExhaustionDetector = cfg.detectVirtualThreadCarrierExhaustion
                ? new VirtualThreadCarrierExhaustionDetector() : null;

        // ---- Phase 7: High-Level Concurrency Patterns ----
        httpClientConcurrencyDetector = cfg.detectHttpClientIssues
                ? new HttpClientConcurrencyDetector() : null;
        streamClosingDetector = cfg.detectStreamClosing
                ? new StreamClosingDetector() : null;
        cacheConcurrencyDetector = cfg.detectCacheConcurrency
                ? new CacheConcurrencyDetector() : null;
        completableFutureChainDetector = cfg.detectCompletableFutureChainIssues
                ? new CompletableFutureChainDetector() : null;

        // ---- Phase 8: Lifecycle & Structural Correctness ----
        executorShutdownDetector = cfg.detectExecutorShutdown
                ? new ExecutorShutdownDetector() : null;
        mutableMapKeyDetector = cfg.detectMutableMapKeys
                ? new MutableMapKeyDetector() : null;
        nestedMonitorLockoutDetector = cfg.detectNestedMonitorLockout
                ? new NestedMonitorLockoutDetector() : null;
        lockDowngradeDetector = cfg.detectLockDowngrade
                ? new LockDowngradeDetector() : null;
        inheritableThreadLocalMisuseDetector = cfg.detectInheritableThreadLocalMisuse
                ? new InheritableThreadLocalMisuseDetector() : null;
        uncommittedChangesDetector = cfg.detectUncommittedChanges
                ? new UncommittedChangesDetector() : null;

        // ---- Phase 10: API Traps & Subtle Concurrency Bugs ----
        threadLocalContaminationDetector = cfg.detectThreadLocalContamination
                ? new ThreadLocalContaminationDetector() : null;
        atomicNonAtomicUpdateDetector = cfg.detectAtomicNonAtomicUpdates
                ? new AtomicNonAtomicUpdateDetector() : null;
        synchronizedCollectionIterationDetector = cfg.detectSynchronizedCollectionIteration
                ? new SynchronizedCollectionIterationDetector() : null;
        sharedFormatterDetector = cfg.detectSharedFormatter
                ? new SharedFormatterDetector() : null;
        concurrentMapComputeRecursionDetector = cfg.detectConcurrentMapComputeRecursion
                ? new ConcurrentMapComputeRecursionDetector() : null;
        synchronizedOnLiteralDetector = cfg.detectSynchronizedOnLiteral
                ? new SynchronizedOnLiteralDetector() : null;
        publicLockExposureDetector = cfg.detectPublicLockExposure
                ? new PublicLockExposureDetector() : null;
        forkJoinTaskBlockingDetector = cfg.detectForkJoinTaskBlocking
                ? new ForkJoinTaskBlockingDetector() : null;
        optimisticReadValidationDetector = cfg.detectOptimisticReadValidation
                ? new OptimisticReadValidationDetector() : null;
        cfCommonPoolBlockingDetector = cfg.detectCFCommonPoolBlocking
                ? new CompletableFutureCommonPoolBlockingDetector() : null;

        // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----
        sharedMatcherDetector       = cfg.detectSharedMatcher       ? new SharedMatcherDetector()       : null;
        sharedDecimalFormatDetector = cfg.detectSharedDecimalFormat  ? new SharedDecimalFormatDetector() : null;
        weakReferenceRaceDetector   = cfg.detectWeakReferenceRace    ? new WeakReferenceRaceDetector()   : null;
        statefulLambdaDetector      = cfg.detectStatefulLambda       ? new StatefulLambdaDetector()      : null;
        sharedMessageDigestDetector = cfg.detectSharedMessageDigest  ? new SharedMessageDigestDetector() : null;

        // ---- Phase 12: Operational & Hygiene Concurrency Issues ----
        interruptSwallowingDetector      = cfg.detectInterruptSwallowing     ? new InterruptSwallowingDetector()      : null;
        mdcContextLeakDetector           = cfg.detectMdcContextLeak          ? new MdcContextLeakDetector()           : null;
        systemPropertyMutationDetector   = cfg.detectSystemPropertyMutation  ? new SystemPropertyMutationDetector()   : null;
        futureIgnoredDetector            = cfg.detectFutureIgnored           ? new FutureIgnoredDetector()            : null;
        explicitGcDetector               = cfg.detectExplicitGc              ? new ExplicitGcDetector()               : null;
        deprecatedThreadApiDetector      = cfg.detectDeprecatedThreadApi     ? new DeprecatedThreadApiDetector()      : null;
        sharedXmlParserDetector          = cfg.detectSharedXmlParser         ? new SharedXmlParserDetector()          : null;
        boxedPrimitiveLockDetector       = cfg.detectBoxedPrimitiveLock      ? new BoxedPrimitiveLockDetector()       : null;
        sharedTimeZoneDetector           = cfg.detectSharedTimeZone          ? new SharedTimeZoneDetector()           : null;
        uncaughtExceptionHandlerDetector = cfg.detectUncaughtExceptionHandler ? new UncaughtExceptionHandlerDetector() : null;

        // ---- Phase 13: Additional concurrency-bug categories (1.0.0+) ----
        daemonThreadHygieneDetector  = cfg.detectDaemonThreadHygiene  ? new DaemonThreadHygieneDetector()  : null;
        notifyWithoutMonitorDetector = cfg.detectNotifyWithoutMonitor ? new NotifyWithoutMonitorDetector() : null;
        sharedSecureRandomDetector   = cfg.detectSharedSecureRandom   ? new SharedSecureRandomDetector()   : null;
        weakHashMapSharedDetector    = cfg.detectWeakHashMapShared    ? new WeakHashMapSharedDetector()    : null;
        jdbcConnectionSharedDetector = cfg.detectJdbcConnectionShared ? new JdbcConnectionSharedDetector() : null;

        // ---- Phase 14: Additional thread-unsafe primitives & publication hazards (1.7.0+) ----
        sharedStatefulCryptoDetector         = cfg.detectSharedStatefulCrypto      ? new SharedStatefulCryptoDetector()         : null;
        nonAtomicConcurrentMapUpdateDetector = cfg.detectConcurrentMapCheckThenAct ? new NonAtomicConcurrentMapUpdateDetector() : null;
        sharedDeflaterDetector               = cfg.detectSharedDeflater            ? new SharedDeflaterDetector()               : null;
        thisEscapeDetector                   = cfg.detectThisEscape                ? new ThisEscapeDetector()                   : null;
        threadLocalRandomMisuseDetector      = cfg.detectThreadLocalRandomMisuse   ? new ThreadLocalRandomMisuseDetector()      : null;
        // Phase 15
        completableFutureObtrudeDetector = cfg.detectCompletableFutureObtrudeAbuse ? new CompletableFutureObtrudeDetector() : null;
        spuriousWakeupHazardDetector     = cfg.detectSpuriousWakeupHazard          ? new SpuriousWakeupDetector()           : null;
        lockUpgradeDeadlockDetector      = cfg.detectLockUpgradeDeadlock           ? new LockUpgradeDeadlockDetector()      : null;
        tryLockMisuseDetector            = cfg.detectTryLockMisuse                 ? new TryLockMisuseDetector()            : null;
        cfBlockingCallbackDetector       = cfg.detectCFBlockingCallback            ? new CompletableFutureBlockingCallbackDetector() : null;
        // ---- Phase 16: JDK 25/26 preview-era concurrency detectors ----
        stableValueMisuseDetector         = cfg.detectStableValueMisuse            ? new StableValueMisuseDetector()         : null;
        structuredTaskScopeMisuseDetector = cfg.detectStructuredTaskScopeMisuse    ? new StructuredTaskScopeMisuseDetector() : null;
        gathererConcurrencyMisuseDetector = cfg.detectGathererConcurrencyMisuse    ? new GathererConcurrencyMisuseDetector() : null;
        // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----
        sharedByteBufferDetector         = cfg.detectSharedByteBuffer         ? new SharedByteBufferDetector()         : null;
        sharedCharsetCoderDetector       = cfg.detectSharedCharsetCoder       ? new SharedCharsetCoderDetector()       : null;
        sharedChecksumDetector           = cfg.detectSharedChecksum           ? new SharedChecksumDetector()           : null;
        fileChannelPositionRaceDetector  = cfg.detectFileChannelPositionRace  ? new FileChannelPositionRaceDetector()  : null;
        sharedIteratorDetector           = cfg.detectSharedIterator           ? new SharedIteratorDetector()           : null;
        highContentionAtomicDetector     = cfg.detectHighContentionAtomic     ? new HighContentionAtomicDetector()     : null;
        sharedJsonMapperReconfigDetector = cfg.detectSharedJsonMapperReconfig ? new SharedJsonMapperReconfigDetector() : null;
    }

    /**
     * Runs every enabled Phase 2 detector's analysis and returns the
     * {@code toString()} of any that report issues.
     *
     * <p>Called by {@link se.deversity.asynctest.runner.ConcurrencyRunner} after the
     * test completes or times out.
     *
     * @return list of non-empty issue reports; never {@code null}
     */
    List<String> analyzeAll() {
        List<String> out = new ArrayList<>();

        // ---- Phase 1 ----
        ifIssue(deadlockDetector,
                DeadlockDetector::analyze,
                DeadlockDetector.DeadlockReport::hasIssues, out);
        ifIssue(visibilityMonitor,
                VisibilityMonitor::analyzeVisibility,
                VisibilityMonitor.VisibilityReport::hasIssues, out);
        ifIssue(livelockDetector,
                LivelockDetector::analyzeLivelocks,
                LivelockDetector.LivelockReport::hasIssues, out);

        ifIssue(falseSharingDetector,
                FalseSharingDetector::analyzeFalseSharing,
                FalseSharingDetector.FalseSharingReport::hasIssues, out);
        ifIssue(wakeupDetector,
                WakeupDetector::analyzeWakeups,
                WakeupDetector.WakeupReport::hasIssues, out);
        ifIssue(constructorSafetyValidator,
                ConstructorSafetyValidator::validateConstructorSafety,
                ConstructorSafetyValidator.ConstructorSafetyReport::hasIssues, out);
        ifIssue(abaProblemDetector,
                ABAProblemDetector::analyzeABA,
                ABAProblemDetector.ABAReport::hasIssues, out);
        ifIssue(lockOrderValidator,
                LockOrderValidator::validateLockOrder,
                LockOrderValidator.LockOrderReport::hasIssues, out);
        ifIssue(synchronizerMonitor,
                SynchronizerMonitor::analyzeSynchronizers,
                SynchronizerMonitor.SynchronizerReport::hasIssues, out);
        ifIssue(threadPoolMonitor,
                ThreadPoolMonitor::analyzePoolHealth,
                ThreadPoolMonitor.ThreadPoolReport::hasIssues, out);
        ifIssue(memoryOrderingMonitor,
                MemoryOrderingMonitor::analyzeOrdering,
                MemoryOrderingMonitor.MemoryOrderingReport::hasIssues, out);
        ifIssue(pipelineMonitor,
                PipelineMonitor::analyzePipeline,
                PipelineMonitor.PipelineReport::hasIssues, out);
        // ReadWriteLock uses hasFairnessIssues() — report it as an issue when
        // writer starvation or imbalance is detected
        if (readWriteLockMonitor != null) {
            ReadWriteLockMonitor.ReadWriteLockReport r = readWriteLockMonitor.analyzeFairness();
            if (r.hasFairnessIssues()) out.add(r.toString());
        }
        ifIssue(semaphoreMisuseDetector,
                SemaphoreMisuseDetector::analyze,
                SemaphoreMisuseDetector.SemaphoreMisuseReport::hasIssues, out);
        ifIssue(completableFutureExceptionDetector,
                CompletableFutureExceptionDetector::analyze,
                CompletableFutureExceptionDetector.CompletableFutureExceptionReport::hasIssues, out);
        if (completableFutureCompletionLeakDetector != null) {
            CompletableFutureCompletionLeakDetector.CompletionLeakReport r = 
                completableFutureCompletionLeakDetector.analyze();
            if (r.hasLeaks()) out.add(r.toString());
        }
        if (virtualThreadPinningDetector != null) {
            VirtualThreadPinningDetector.PinningReport r = 
                virtualThreadPinningDetector.analyzePinning();
            if (r.hasPinningIssues()) out.add(r.toString());
        }
        if (threadPoolDeadlockDetector != null) {
            ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport r = 
                threadPoolDeadlockDetector.analyze();
            if (r.hasDeadlockRisk()) out.add(r.toString());
        }
        ifIssue(concurrentModificationDetector,
                ConcurrentModificationDetector::analyze,
                ConcurrentModificationDetector.ConcurrentModificationReport::hasIssues, out);
        ifIssue(lockLeakDetector,
                LockLeakDetector::analyze,
                LockLeakDetector.LockLeakReport::hasIssues, out);
        ifIssue(sharedRandomDetector,
                SharedRandomDetector::analyze,
                SharedRandomDetector.SharedRandomReport::hasIssues, out);
        ifIssue(blockingQueueDetector,
                BlockingQueueDetector::analyze,
                BlockingQueueDetector.BlockingQueueReport::hasIssues, out);
        ifIssue(conditionVariableDetector,
                ConditionVariableDetector::analyze,
                ConditionVariableDetector.ConditionVariableReport::hasIssues, out);
        ifIssue(simpleDateFormatDetector,
                SimpleDateFormatDetector::analyze,
                SimpleDateFormatDetector.SimpleDateFormatReport::hasIssues, out);
        ifIssue(parallelStreamDetector,
                ParallelStreamDetector::analyze,
                ParallelStreamDetector.ParallelStreamReport::hasIssues, out);
        ifIssue(resourceLeakDetector,
                ResourceLeakDetector::analyze,
                ResourceLeakDetector.ResourceLeakReport::hasIssues, out);
        ifIssue(countDownLatchDetector,
                CountDownLatchDetector::analyze,
                CountDownLatchDetector.CountDownLatchReport::hasIssues, out);
        ifIssue(cyclicBarrierDetector,
                CyclicBarrierDetector::analyze,
                CyclicBarrierDetector.CyclicBarrierReport::hasIssues, out);
        ifIssue(reentrantLockDetector,
                ReentrantLockDetector::analyze,
                ReentrantLockDetector.ReentrantLockReport::hasIssues, out);
        ifIssue(volatileArrayDetector,
                VolatileArrayDetector::analyze,
                VolatileArrayDetector.VolatileArrayReport::hasIssues, out);
        ifIssue(doubleCheckedLockingDetector,
                DoubleCheckedLockingDetector::analyze,
                DoubleCheckedLockingDetector.DoubleCheckedLockingReport::hasIssues, out);
        ifIssue(waitTimeoutDetector,
                WaitTimeoutDetector::analyze,
                WaitTimeoutDetector.WaitTimeoutReport::hasIssues, out);
        ifIssue(lockContentionDetector,
                LockContentionDetector::analyze,
                LockContentionDetector.LockContentionReport::hasIssues, out);
        ifIssue(synchronizedNonFinalDetector,
                SynchronizedNonFinalDetector::analyze,
                SynchronizedNonFinalDetector.SynchronizedNonFinalReport::hasIssues, out);
        ifIssue(missedSignalDetector,
                MissedSignalDetector::analyze,
                MissedSignalDetector.MissedSignalReport::hasIssues, out);
        ifIssue(lazyInitRaceDetector,
                LazyInitRaceDetector::analyze,
                LazyInitRaceDetector.LazyInitRaceReport::hasIssues, out);
        ifIssue(phaserDetector,
                PhaserDetector::analyze,
                PhaserDetector.PhaserReport::hasIssues, out);
        ifIssue(stampedLockDetector,
                StampedLockDetector::analyze,
                StampedLockDetector.StampedLockReport::hasIssues, out);
        ifIssue(exchangerDetector,
                ExchangerDetector::analyze,
                ExchangerDetector.ExchangerReport::hasIssues, out);
        ifIssue(scheduledExecutorDetector,
                ScheduledExecutorDetector::analyze,
                ScheduledExecutorDetector.ScheduledExecutorReport::hasIssues, out);
        ifIssue(forkJoinPoolDetector,
                ForkJoinPoolDetector::analyze,
                ForkJoinPoolDetector.ForkJoinPoolReport::hasIssues, out);
        ifIssue(threadFactoryDetector,
                ThreadFactoryDetector::analyze,
                ThreadFactoryDetector.ThreadFactoryReport::hasIssues, out);

        // ---- Phase 3 ----
        ifIssue(raceConditionDetector,
                RaceConditionDetector::analyzeRaceConditions,
                RaceConditionDetector.RaceConditionReport::hasIssues, out);
        ifIssue(threadLocalMonitor,
                ThreadLocalMonitor::analyzeThreadLocalLeaks,
                ThreadLocalMonitor.ThreadLocalReport::hasIssues, out);
        ifIssue(busyWaitDetector,
                BusyWaitDetector::analyzeBusyWaiting,
                BusyWaitDetector.BusyWaitReport::hasIssues, out);
        ifIssue(atomicityValidator,
                AtomicityValidator::analyzeAtomicity,
                AtomicityValidator.AtomicityReport::hasIssues, out);
        ifIssue(interruptMonitor,
                InterruptMonitor::analyzeInterruptHandling,
                InterruptMonitor.InterruptReport::hasIssues, out);

        // ---- Phase 4: Infrastructure & Resource Management ----
        ifIssue(threadLeakDetector,
                ThreadLeakDetector::analyzeLeaks,
                ThreadLeakDetector.ThreadLeakReport::hasIssues, out);
        ifIssue(sleepInLockDetector,
                SleepInLockDetector::analyze,
                SleepInLockDetector.SleepInLockReport::hasIssues, out);
        ifIssue(unboundedQueueDetector,
                UnboundedQueueDetector::analyze,
                UnboundedQueueDetector.UnboundedQueueReport::hasIssues, out);
        ifIssue(threadStarvationDetector,
                ThreadStarvationDetector::analyze,
                ThreadStarvationDetector.ThreadStarvationReport::hasIssues, out);

        // ---- Phase 5: Thread-Safety of Common Types ----
        ifIssue(calendarDetector,
                CalendarDetector::analyze,
                CalendarDetector.CalendarReport::hasIssues, out);
        ifIssue(sharedCollectionDetector,
                SharedCollectionDetector::analyze,
                SharedCollectionDetector.SharedCollectionReport::hasIssues, out);
        ifIssue(timerDetector,
                TimerDetector::analyze,
                TimerDetector.TimerReport::hasIssues, out);
        ifIssue(copyOnWriteCollectionDetector,
                CopyOnWriteCollectionDetector::analyze,
                CopyOnWriteCollectionDetector.CopyOnWriteReport::hasIssues, out);
        ifIssue(stringBuilderDetector,
                StringBuilderDetector::analyze,
                StringBuilderDetector.StringBuilderReport::hasIssues, out);

        // ---- Phase 6: Virtual Thread Concurrency ----
        ifIssue(structuredConcurrencyMisuseDetector,
                StructuredConcurrencyMisuseDetector::analyze,
                StructuredConcurrencyMisuseDetector.StructuredConcurrencyReport::hasIssues, out);
        ifIssue(virtualThreadContextLeakDetector,
                VirtualThreadContextLeakDetector::analyze,
                VirtualThreadContextLeakDetector.VirtualThreadContextLeakReport::hasIssues, out);
        ifIssue(scopedValueMisuseDetector,
                ScopedValueMisuseDetector::analyze,
                ScopedValueMisuseDetector.ScopedValueMisuseReport::hasIssues, out);
        ifIssue(virtualThreadCpuBoundTaskDetector,
                VirtualThreadCpuBoundTaskDetector::analyze,
                VirtualThreadCpuBoundTaskDetector.CpuBoundTaskReport::hasIssues, out);
        ifIssue(virtualThreadCarrierExhaustionDetector,
                VirtualThreadCarrierExhaustionDetector::analyze,
                VirtualThreadCarrierExhaustionDetector.CarrierExhaustionReport::hasIssues, out);

        // ---- Phase 7: High-Level Concurrency Patterns ----
        ifIssue(httpClientConcurrencyDetector,
                HttpClientConcurrencyDetector::analyze,
                HttpClientConcurrencyDetector.HttpClientConcurrencyReport::hasIssues, out);
        ifIssue(streamClosingDetector,
                StreamClosingDetector::analyze,
                StreamClosingDetector.StreamClosingReport::hasIssues, out);
        ifIssue(cacheConcurrencyDetector,
                CacheConcurrencyDetector::analyze,
                CacheConcurrencyDetector.CacheConcurrencyReport::hasIssues, out);
        ifIssue(completableFutureChainDetector,
                CompletableFutureChainDetector::analyze,
                CompletableFutureChainDetector.CompletableFutureChainReport::hasIssues, out);

        // ---- Phase 8: Lifecycle & Structural Correctness ----
        ifIssue(executorShutdownDetector,
                ExecutorShutdownDetector::analyze,
                ExecutorShutdownDetector.ExecutorShutdownReport::hasIssues, out);
        ifIssue(mutableMapKeyDetector,
                MutableMapKeyDetector::analyze,
                MutableMapKeyDetector.MutableMapKeyReport::hasIssues, out);
        ifIssue(nestedMonitorLockoutDetector,
                NestedMonitorLockoutDetector::analyze,
                NestedMonitorLockoutDetector.NestedMonitorLockoutReport::hasIssues, out);
        ifIssue(lockDowngradeDetector,
                LockDowngradeDetector::analyze,
                LockDowngradeDetector.LockDowngradeReport::hasIssues, out);
        ifIssue(inheritableThreadLocalMisuseDetector,
                InheritableThreadLocalMisuseDetector::analyze,
                InheritableThreadLocalMisuseDetector.InheritableThreadLocalReport::hasIssues, out);
        ifIssue(uncommittedChangesDetector,
                UncommittedChangesDetector::analyze,
                UncommittedChangesDetector.UncommittedChangesReport::hasIssues, out);

        // ---- Phase 10: API Traps & Subtle Concurrency Bugs ----
        ifIssue(threadLocalContaminationDetector,
                ThreadLocalContaminationDetector::analyze,
                ThreadLocalContaminationDetector.ThreadLocalContaminationReport::hasIssues, out);
        ifIssue(atomicNonAtomicUpdateDetector,
                AtomicNonAtomicUpdateDetector::analyze,
                AtomicNonAtomicUpdateDetector.AtomicNonAtomicUpdateReport::hasIssues, out);
        ifIssue(synchronizedCollectionIterationDetector,
                SynchronizedCollectionIterationDetector::analyze,
                SynchronizedCollectionIterationDetector.SynchronizedCollectionIterationReport::hasIssues, out);
        ifIssue(sharedFormatterDetector,
                SharedFormatterDetector::analyze,
                SharedFormatterDetector.SharedFormatterReport::hasIssues, out);
        ifIssue(concurrentMapComputeRecursionDetector,
                ConcurrentMapComputeRecursionDetector::analyze,
                ConcurrentMapComputeRecursionDetector.ConcurrentMapComputeRecursionReport::hasIssues, out);
        ifIssue(synchronizedOnLiteralDetector,
                SynchronizedOnLiteralDetector::analyze,
                SynchronizedOnLiteralDetector.SynchronizedOnLiteralReport::hasIssues, out);
        ifIssue(publicLockExposureDetector,
                PublicLockExposureDetector::analyze,
                PublicLockExposureDetector.PublicLockExposureReport::hasIssues, out);
        ifIssue(forkJoinTaskBlockingDetector,
                ForkJoinTaskBlockingDetector::analyze,
                ForkJoinTaskBlockingDetector.ForkJoinTaskBlockingReport::hasIssues, out);
        ifIssue(optimisticReadValidationDetector,
                OptimisticReadValidationDetector::analyze,
                OptimisticReadValidationDetector.OptimisticReadValidationReport::hasIssues, out);
        ifIssue(cfCommonPoolBlockingDetector,
                CompletableFutureCommonPoolBlockingDetector::analyze,
                CompletableFutureCommonPoolBlockingDetector.CompletableFutureCommonPoolBlockingReport::hasIssues, out);

        // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----
        ifIssue(sharedMatcherDetector,
                SharedMatcherDetector::analyze,
                SharedMatcherDetector.SharedMatcherReport::hasIssues, out);
        ifIssue(sharedDecimalFormatDetector,
                SharedDecimalFormatDetector::analyze,
                SharedDecimalFormatDetector.SharedDecimalFormatReport::hasIssues, out);
        ifIssue(weakReferenceRaceDetector,
                WeakReferenceRaceDetector::analyze,
                WeakReferenceRaceDetector.WeakReferenceRaceReport::hasIssues, out);
        ifIssue(statefulLambdaDetector,
                StatefulLambdaDetector::analyze,
                StatefulLambdaDetector.StatefulLambdaReport::hasIssues, out);
        ifIssue(sharedMessageDigestDetector,
                SharedMessageDigestDetector::analyze,
                SharedMessageDigestDetector.SharedMessageDigestReport::hasIssues, out);

        // ---- Phase 12: Operational & Hygiene Concurrency Issues ----
        ifIssue(interruptSwallowingDetector,
                InterruptSwallowingDetector::analyze,
                InterruptSwallowingDetector.InterruptSwallowingReport::hasIssues, out);
        ifIssue(mdcContextLeakDetector,
                MdcContextLeakDetector::analyze,
                MdcContextLeakDetector.MdcContextLeakReport::hasIssues, out);
        ifIssue(systemPropertyMutationDetector,
                SystemPropertyMutationDetector::analyze,
                SystemPropertyMutationDetector.SystemPropertyMutationReport::hasIssues, out);
        ifIssue(futureIgnoredDetector,
                FutureIgnoredDetector::analyze,
                FutureIgnoredDetector.FutureIgnoredReport::hasIssues, out);
        ifIssue(explicitGcDetector,
                ExplicitGcDetector::analyze,
                ExplicitGcDetector.ExplicitGcReport::hasIssues, out);
        ifIssue(deprecatedThreadApiDetector,
                DeprecatedThreadApiDetector::analyze,
                DeprecatedThreadApiDetector.DeprecatedThreadApiReport::hasIssues, out);
        ifIssue(sharedXmlParserDetector,
                SharedXmlParserDetector::analyze,
                SharedXmlParserDetector.SharedXmlParserReport::hasIssues, out);
        ifIssue(boxedPrimitiveLockDetector,
                BoxedPrimitiveLockDetector::analyze,
                BoxedPrimitiveLockDetector.BoxedPrimitiveLockReport::hasIssues, out);
        ifIssue(sharedTimeZoneDetector,
                SharedTimeZoneDetector::analyze,
                SharedTimeZoneDetector.SharedTimeZoneReport::hasIssues, out);
        ifIssue(uncaughtExceptionHandlerDetector,
                UncaughtExceptionHandlerDetector::analyze,
                UncaughtExceptionHandlerDetector.UncaughtExceptionHandlerReport::hasIssues, out);

        // ---- Phase 13 (1.0.0+) ----
        ifIssue(daemonThreadHygieneDetector,
                DaemonThreadHygieneDetector::analyze,
                DaemonThreadHygieneDetector.Report::hasIssues, out);
        ifIssue(notifyWithoutMonitorDetector,
                NotifyWithoutMonitorDetector::analyze,
                NotifyWithoutMonitorDetector.Report::hasIssues, out);
        ifIssue(sharedSecureRandomDetector,
                SharedSecureRandomDetector::analyze,
                SharedSecureRandomDetector.Report::hasIssues, out);
        ifIssue(weakHashMapSharedDetector,
                WeakHashMapSharedDetector::analyze,
                WeakHashMapSharedDetector.Report::hasIssues, out);
        ifIssue(jdbcConnectionSharedDetector,
                JdbcConnectionSharedDetector::analyze,
                JdbcConnectionSharedDetector.Report::hasIssues, out);

        // ---- Phase 14 (1.7.0+) ----
        ifIssue(sharedStatefulCryptoDetector,
                SharedStatefulCryptoDetector::analyze,
                SharedStatefulCryptoDetector.Report::hasIssues, out);
        ifIssue(nonAtomicConcurrentMapUpdateDetector,
                NonAtomicConcurrentMapUpdateDetector::analyze,
                NonAtomicConcurrentMapUpdateDetector.Report::hasIssues, out);
        ifIssue(sharedDeflaterDetector,
                SharedDeflaterDetector::analyze,
                SharedDeflaterDetector.Report::hasIssues, out);
        ifIssue(thisEscapeDetector,
                ThisEscapeDetector::analyze,
                ThisEscapeDetector.Report::hasIssues, out);
        ifIssue(threadLocalRandomMisuseDetector,
                ThreadLocalRandomMisuseDetector::analyze,
                ThreadLocalRandomMisuseDetector.Report::hasIssues, out);

        // ---- Phase 15 (1.8.0+) ----
        ifIssue(completableFutureObtrudeDetector,
                CompletableFutureObtrudeDetector::analyze,
                CompletableFutureObtrudeDetector.Report::hasIssues, out);
        ifIssue(spuriousWakeupHazardDetector,
                SpuriousWakeupDetector::analyze,
                SpuriousWakeupDetector.Report::hasIssues, out);
        ifIssue(lockUpgradeDeadlockDetector,
                LockUpgradeDeadlockDetector::analyze,
                LockUpgradeDeadlockDetector.Report::hasIssues, out);
        ifIssue(tryLockMisuseDetector,
                TryLockMisuseDetector::analyze,
                TryLockMisuseDetector.Report::hasIssues, out);
        ifIssue(cfBlockingCallbackDetector,
                CompletableFutureBlockingCallbackDetector::analyze,
                CompletableFutureBlockingCallbackDetector.Report::hasIssues, out);

        // ---- Phase 16: JDK 25/26 preview-era concurrency detectors ----
        ifIssue(stableValueMisuseDetector,
                StableValueMisuseDetector::analyze,
                StableValueMisuseDetector.StableValueMisuseReport::hasIssues, out);
        ifIssue(structuredTaskScopeMisuseDetector,
                StructuredTaskScopeMisuseDetector::analyze,
                StructuredTaskScopeMisuseDetector.StructuredTaskScopeMisuseReport::hasIssues, out);
        ifIssue(gathererConcurrencyMisuseDetector,
                GathererConcurrencyMisuseDetector::analyze,
                GathererConcurrencyMisuseDetector.GathererConcurrencyMisuseReport::hasIssues, out);

        // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----
        ifIssue(sharedByteBufferDetector,
                SharedByteBufferDetector::analyze,
                SharedByteBufferDetector.Report::hasIssues, out);
        ifIssue(sharedCharsetCoderDetector,
                SharedCharsetCoderDetector::analyze,
                SharedCharsetCoderDetector.Report::hasIssues, out);
        ifIssue(sharedChecksumDetector,
                SharedChecksumDetector::analyze,
                SharedChecksumDetector.Report::hasIssues, out);
        ifIssue(fileChannelPositionRaceDetector,
                FileChannelPositionRaceDetector::analyze,
                FileChannelPositionRaceDetector.Report::hasIssues, out);
        ifIssue(sharedIteratorDetector,
                SharedIteratorDetector::analyze,
                SharedIteratorDetector.Report::hasIssues, out);
        ifIssue(highContentionAtomicDetector,
                HighContentionAtomicDetector::analyze,
                HighContentionAtomicDetector.Report::hasIssues, out);
        ifIssue(sharedJsonMapperReconfigDetector,
                SharedJsonMapperReconfigDetector::analyze,
                SharedJsonMapperReconfigDetector.Report::hasIssues, out);

        return out;
    }

    // ---- Helper ----

    /**
     * If {@code detector} is non-null and the report from {@code analyze} has issues,
     * appends the report's {@code toString()} to {@code out}.
     */
    private static <D, R> void ifIssue(D detector,
                                       Function<D, R> analyze,
                                       Function<R, Boolean> hasIssues,
                                       List<String> out) {
        if (detector == null) return;
        R report = analyze.apply(detector);
        if (Boolean.TRUE.equals(hasIssues.apply(report))) {
            out.add(report.toString());
        }
    }
}
