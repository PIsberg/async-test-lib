package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;
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
import se.deversity.asynctest.diagnostics.LazyConstantMisuseDetector;
import se.deversity.asynctest.diagnostics.FinalFieldMutationDetector;
import se.deversity.asynctest.diagnostics.SharedKdfDetector;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;
import se.deversity.asynctest.diagnostics.ExecutorDeadlockDetector;
import se.deversity.asynctest.diagnostics.FlowPublisherConcurrencyDetector;
import se.deversity.asynctest.diagnostics.FutureBlockingDetector;
import se.deversity.asynctest.diagnostics.ConfinedArenaThreadEscapeDetector;
import se.deversity.asynctest.diagnostics.SharedMemorySegmentRaceDetector;
import se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector;
import se.deversity.asynctest.diagnostics.RecordMutableComponentLeakDetector;
import se.deversity.asynctest.diagnostics.StaticInitDeadlockDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadPoolingDetector;
import se.deversity.asynctest.diagnostics.PlatformThreadPerTaskDetector;
import se.deversity.asynctest.diagnostics.SharedSplittableRandomDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCompletionRaceDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCancellationPropagationDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCombinatorMisuseDetector;
import se.deversity.asynctest.diagnostics.LambdaLostUpdateDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadResourceSaturationDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadMonitorSerializationDetector;
import se.deversity.asynctest.diagnostics.ThreadLocalCacheDegradationDetector;
import se.deversity.asynctest.diagnostics.ScopeJoinerMisuseDetector;
import se.deversity.asynctest.diagnostics.ScopeConfigurationMisuseDetector;
import se.deversity.asynctest.diagnostics.ScopeResultEscapeDetector;
import se.deversity.asynctest.diagnostics.LazyCollectionMisuseDetector;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    final @Nullable DeadlockDetector  deadlockDetector;
    final @Nullable VisibilityMonitor visibilityMonitor;
    final @Nullable LivelockDetector  livelockDetector;

    // ---- Phase 2: Core ----
    final @Nullable FalseSharingDetector       falseSharingDetector;
    final @Nullable WakeupDetector             wakeupDetector;
    final @Nullable ConstructorSafetyValidator constructorSafetyValidator;
    final @Nullable ABAProblemDetector         abaProblemDetector;
    final @Nullable LockOrderValidator         lockOrderValidator;
    final @Nullable SynchronizerMonitor        synchronizerMonitor;
    final @Nullable ThreadPoolMonitor          threadPoolMonitor;
    final @Nullable MemoryOrderingMonitor      memoryOrderingMonitor;
    final @Nullable PipelineMonitor            pipelineMonitor;
    final @Nullable ReadWriteLockMonitor       readWriteLockMonitor;

    // ---- Phase 2: Additional monitors ----
    final @Nullable SemaphoreMisuseDetector              semaphoreMisuseDetector;
    final @Nullable CompletableFutureExceptionDetector   completableFutureExceptionDetector;
    final @Nullable CompletableFutureCompletionLeakDetector completableFutureCompletionLeakDetector;
    final @Nullable VirtualThreadPinningDetector         virtualThreadPinningDetector;
    final @Nullable ThreadPoolDeadlockDetector           threadPoolDeadlockDetector;
    final @Nullable ConcurrentModificationDetector       concurrentModificationDetector;
    final @Nullable LockLeakDetector                     lockLeakDetector;
    final @Nullable SharedRandomDetector                 sharedRandomDetector;
    final @Nullable BlockingQueueDetector                blockingQueueDetector;
    final @Nullable ConditionVariableDetector            conditionVariableDetector;
    final @Nullable SimpleDateFormatDetector             simpleDateFormatDetector;
    final @Nullable ParallelStreamDetector               parallelStreamDetector;
    final @Nullable ResourceLeakDetector                 resourceLeakDetector;

    // ---- Phase 2: Additional concurrency ----
    final @Nullable CountDownLatchDetector           countDownLatchDetector;
    final @Nullable CyclicBarrierDetector            cyclicBarrierDetector;
    final @Nullable ReentrantLockDetector            reentrantLockDetector;
    final @Nullable VolatileArrayDetector            volatileArrayDetector;
    final @Nullable DoubleCheckedLockingDetector     doubleCheckedLockingDetector;
    final @Nullable WaitTimeoutDetector              waitTimeoutDetector;
    final @Nullable LockContentionDetector           lockContentionDetector;
    final @Nullable SynchronizedNonFinalDetector     synchronizedNonFinalDetector;
    final @Nullable MissedSignalDetector             missedSignalDetector;
    final @Nullable LazyInitRaceDetector             lazyInitRaceDetector;

    // ---- Phase 2: Advanced concurrency utilities ----
    final @Nullable PhaserDetector             phaserDetector;
    final @Nullable StampedLockDetector        stampedLockDetector;
    final @Nullable ExchangerDetector          exchangerDetector;
    final @Nullable ScheduledExecutorDetector  scheduledExecutorDetector;
    final @Nullable ForkJoinPoolDetector       forkJoinPoolDetector;
    final @Nullable ThreadFactoryDetector      threadFactoryDetector;

    // ---- Phase 3 ----
    final @Nullable RaceConditionDetector raceConditionDetector;
    final @Nullable ThreadLocalMonitor    threadLocalMonitor;
    final @Nullable BusyWaitDetector      busyWaitDetector;
    final @Nullable AtomicityValidator    atomicityValidator;
    final @Nullable InterruptMonitor      interruptMonitor;

    // ---- Phase 4: Infrastructure & Resource Management ----
    final @Nullable ThreadLeakDetector         threadLeakDetector;
    final @Nullable SleepInLockDetector        sleepInLockDetector;
    final @Nullable UnboundedQueueDetector     unboundedQueueDetector;
    final @Nullable ThreadStarvationDetector   threadStarvationDetector;

    // ---- Phase 5: Thread-Safety of Common Types ----
    final @Nullable CalendarDetector              calendarDetector;
    final @Nullable SharedCollectionDetector      sharedCollectionDetector;
    final @Nullable TimerDetector                 timerDetector;
    final @Nullable CopyOnWriteCollectionDetector copyOnWriteCollectionDetector;
    final @Nullable StringBuilderDetector         stringBuilderDetector;

    // ---- Phase 6: Virtual Thread Concurrency (Java 21+) ----
    final @Nullable StructuredConcurrencyMisuseDetector  structuredConcurrencyMisuseDetector;
    final @Nullable VirtualThreadContextLeakDetector     virtualThreadContextLeakDetector;
    final @Nullable ScopedValueMisuseDetector            scopedValueMisuseDetector;
    final @Nullable VirtualThreadCpuBoundTaskDetector    virtualThreadCpuBoundTaskDetector;
    final @Nullable VirtualThreadCarrierExhaustionDetector virtualThreadCarrierExhaustionDetector;

    // ---- Phase 7: High-Level Concurrency Patterns ----
    final @Nullable HttpClientConcurrencyDetector       httpClientConcurrencyDetector;
    final @Nullable StreamClosingDetector               streamClosingDetector;
    final @Nullable CacheConcurrencyDetector            cacheConcurrencyDetector;
    final @Nullable CompletableFutureChainDetector      completableFutureChainDetector;

    // ---- Phase 8: Lifecycle & Structural Correctness ----
    final @Nullable ExecutorShutdownDetector            executorShutdownDetector;
    final @Nullable MutableMapKeyDetector               mutableMapKeyDetector;
    final @Nullable NestedMonitorLockoutDetector        nestedMonitorLockoutDetector;
    final @Nullable LockDowngradeDetector               lockDowngradeDetector;
    final @Nullable InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseDetector;

    // ---- Phase 10: API Traps & Subtle Concurrency Bugs ----
    final @Nullable ThreadLocalContaminationDetector         threadLocalContaminationDetector;
    final @Nullable AtomicNonAtomicUpdateDetector            atomicNonAtomicUpdateDetector;
    final @Nullable SynchronizedCollectionIterationDetector  synchronizedCollectionIterationDetector;
    final @Nullable SharedFormatterDetector                  sharedFormatterDetector;
    final @Nullable ConcurrentMapComputeRecursionDetector    concurrentMapComputeRecursionDetector;
    final @Nullable SynchronizedOnLiteralDetector            synchronizedOnLiteralDetector;
    final @Nullable PublicLockExposureDetector               publicLockExposureDetector;
    final @Nullable ForkJoinTaskBlockingDetector             forkJoinTaskBlockingDetector;
    final @Nullable OptimisticReadValidationDetector         optimisticReadValidationDetector;
    final @Nullable CompletableFutureCommonPoolBlockingDetector cfCommonPoolBlockingDetector;

    // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----
    final @Nullable SharedMatcherDetector        sharedMatcherDetector;
    final @Nullable SharedDecimalFormatDetector  sharedDecimalFormatDetector;
    final @Nullable WeakReferenceRaceDetector    weakReferenceRaceDetector;
    final @Nullable StatefulLambdaDetector       statefulLambdaDetector;
    final @Nullable SharedMessageDigestDetector  sharedMessageDigestDetector;

    // ---- Phase 12: Operational & Hygiene Concurrency Issues ----
    final @Nullable InterruptSwallowingDetector       interruptSwallowingDetector;
    final @Nullable MdcContextLeakDetector            mdcContextLeakDetector;
    final @Nullable SystemPropertyMutationDetector    systemPropertyMutationDetector;
    final @Nullable FutureIgnoredDetector             futureIgnoredDetector;
    final @Nullable ExplicitGcDetector                explicitGcDetector;
    final @Nullable DeprecatedThreadApiDetector       deprecatedThreadApiDetector;
    final @Nullable SharedXmlParserDetector           sharedXmlParserDetector;
    final @Nullable BoxedPrimitiveLockDetector        boxedPrimitiveLockDetector;
    final @Nullable SharedTimeZoneDetector            sharedTimeZoneDetector;
    final @Nullable UncaughtExceptionHandlerDetector  uncaughtExceptionHandlerDetector;

    // ---- Phase 13: Additional concurrency-bug categories (1.0.0+) ----
    final @Nullable DaemonThreadHygieneDetector       daemonThreadHygieneDetector;
    final @Nullable NotifyWithoutMonitorDetector      notifyWithoutMonitorDetector;
    final @Nullable SharedSecureRandomDetector        sharedSecureRandomDetector;
    final @Nullable WeakHashMapSharedDetector         weakHashMapSharedDetector;
    final @Nullable JdbcConnectionSharedDetector      jdbcConnectionSharedDetector;

    // ---- Phase 14: Additional thread-unsafe primitives & publication hazards (1.7.0+) ----
    final @Nullable SharedStatefulCryptoDetector          sharedStatefulCryptoDetector;
    final @Nullable NonAtomicConcurrentMapUpdateDetector  nonAtomicConcurrentMapUpdateDetector;
    final @Nullable SharedDeflaterDetector                sharedDeflaterDetector;
    final @Nullable ThisEscapeDetector                    thisEscapeDetector;
    final @Nullable ThreadLocalRandomMisuseDetector       threadLocalRandomMisuseDetector;

    // ---- Phase 15: Asynchronous flow & lock-usage hazards (1.8.0+) ----
    final @Nullable CompletableFutureObtrudeDetector          completableFutureObtrudeDetector;
    final @Nullable SpuriousWakeupDetector                    spuriousWakeupHazardDetector;
    final @Nullable LockUpgradeDeadlockDetector               lockUpgradeDeadlockDetector;
    final @Nullable TryLockMisuseDetector                     tryLockMisuseDetector;
    final @Nullable CompletableFutureBlockingCallbackDetector cfBlockingCallbackDetector;

    // ---- Phase 16: JDK 25/26 preview-era concurrency detectors ----
    final @Nullable StableValueMisuseDetector             stableValueMisuseDetector;
    final @Nullable StructuredTaskScopeMisuseDetector     structuredTaskScopeMisuseDetector;
    final @Nullable GathererConcurrencyMisuseDetector     gathererConcurrencyMisuseDetector;

    // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----
    final @Nullable SharedByteBufferDetector              sharedByteBufferDetector;
    final @Nullable SharedCharsetCoderDetector            sharedCharsetCoderDetector;
    final @Nullable SharedChecksumDetector                sharedChecksumDetector;
    final @Nullable FileChannelPositionRaceDetector       fileChannelPositionRaceDetector;
    final @Nullable SharedIteratorDetector                sharedIteratorDetector;
    final @Nullable HighContentionAtomicDetector          highContentionAtomicDetector;
    final @Nullable SharedJsonMapperReconfigDetector      sharedJsonMapperReconfigDetector;

    // ---- Phase 18: JDK 25/26 GA-era concurrency detectors ----
    final @Nullable LazyConstantMisuseDetector            lazyConstantMisuseDetector;
    final @Nullable FinalFieldMutationDetector            finalFieldMutationDetector;
    final @Nullable SharedKdfDetector                     sharedKdfDetector;

    // ---- Executor / future / latch ----
    final @Nullable LatchMisuseDetector                   latchMisuseDetector;
    final @Nullable ExecutorDeadlockDetector              executorDeadlockDetector;
    final @Nullable FutureBlockingDetector                futureBlockingDetector;
    final @Nullable FlowPublisherConcurrencyDetector      flowPublisherConcurrencyDetector;
    final @Nullable ConfinedArenaThreadEscapeDetector     confinedArenaThreadEscapeDetector;
    final @Nullable SharedMemorySegmentRaceDetector       sharedMemorySegmentRaceDetector;
    final @Nullable VarHandleNonAtomicUpdateDetector      varHandleNonAtomicUpdateDetector;
    final @Nullable RecordMutableComponentLeakDetector    recordMutableComponentLeakDetector;
    final @Nullable StaticInitDeadlockDetector            staticInitDeadlockDetector;
    final @Nullable VirtualThreadPoolingDetector          virtualThreadPoolingDetector;
    final @Nullable PlatformThreadPerTaskDetector         platformThreadPerTaskDetector;
    final @Nullable SharedSplittableRandomDetector        sharedSplittableRandomDetector;
    final @Nullable CompletableFutureCompletionRaceDetector          completableFutureCompletionRaceDetector;
    final @Nullable CompletableFutureCancellationPropagationDetector completableFutureCancellationPropagationDetector;
    final @Nullable CompletableFutureCombinatorMisuseDetector        completableFutureCombinatorMisuseDetector;
    final @Nullable LambdaLostUpdateDetector                         lambdaLostUpdateDetector;
    final @Nullable VirtualThreadResourceSaturationDetector          virtualThreadResourceSaturationDetector;
    final @Nullable VirtualThreadMonitorSerializationDetector        virtualThreadMonitorSerializationDetector;
    final @Nullable ThreadLocalCacheDegradationDetector              threadLocalCacheDegradationDetector;
    final @Nullable ScopeJoinerMisuseDetector scopeJoinerMisuseDetector;
    final @Nullable ScopeConfigurationMisuseDetector scopeConfigurationMisuseDetector;
    final @Nullable ScopeResultEscapeDetector scopeResultEscapeDetector;
    final @Nullable LazyCollectionMisuseDetector lazyCollectionMisuseDetector;

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

        // One upgrade is one finding. LockDowngradeDetector sees the same read-to-write upgrade
        // LockUpgradeDeadlockDetector is named for, and a run with both enabled and both fed
        // reported it twice, the second time under a name that describes the opposite operation.
        // Handing the peer over here rather than deleting the finding is what keeps a caller who
        // instruments only the downgrade detector from silently losing it: their recordings are
        // forwarded, so the finding comes out under the right name instead of not at all.
        // See issue #361.
        if (lockDowngradeDetector != null && lockUpgradeDeadlockDetector != null) {
            lockDowngradeDetector.deferUpgradeReportingTo(lockUpgradeDeadlockDetector);
        }

        // findDeadlockedThreads() reports platform threads, so on the default runner the workers
        // colliding on the code under test are exactly the ones it cannot put in a cycle, and a
        // textbook circular wait came back clean. The JVM's own JSON thread dump does carry the
        // wait-for graph on JDKs whose dump names monitors, so the detector reads it - but only
        // when there is something to find there, because it costs a thread dump. See issue #367.
        if (deadlockDetector != null && cfg.useVirtualThreads) {
            deadlockDetector.enableVirtualThreadScan();
        }

        // A leaked hold is LockLeakDetector's finding, and ReentrantLockDetector gates on
        // timeouts and starvation only. Its method names invite a caller to record acquire and
        // release and expect a leak to be reported, so a caller who instrumented that API and
        // nothing else got silence. Forwarding sends the finding to the detector that owns it,
        // the same arrangement the two read-write lock detectors use. See issue #368.
        if (reentrantLockDetector != null && lockLeakDetector != null) {
            reentrantLockDetector.deferLeakReportingTo(lockLeakDetector);
        }
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
        // ---- Phase 18: JDK 25/26 GA-era concurrency detectors ----
        lazyConstantMisuseDetector       = cfg.detectLazyConstantMisuse       ? new LazyConstantMisuseDetector()       : null;
        finalFieldMutationDetector       = cfg.detectFinalFieldMutation       ? new FinalFieldMutationDetector()       : null;
        sharedKdfDetector                = cfg.detectSharedKdf                ? new SharedKdfDetector()                : null;
        latchMisuseDetector              = cfg.detectLatchMisuse              ? new LatchMisuseDetector()              : null;
        executorDeadlockDetector         = cfg.detectExecutorDeadlock         ? new ExecutorDeadlockDetector()         : null;
        futureBlockingDetector           = cfg.detectFutureBlocking           ? new FutureBlockingDetector()           : null;
        flowPublisherConcurrencyDetector = cfg.detectFlowPublisherConcurrency ? new FlowPublisherConcurrencyDetector() : null;
        confinedArenaThreadEscapeDetector  = cfg.detectConfinedArenaThreadEscape  ? new ConfinedArenaThreadEscapeDetector()  : null;
        sharedMemorySegmentRaceDetector    = cfg.detectSharedMemorySegmentRace    ? new SharedMemorySegmentRaceDetector()    : null;
        varHandleNonAtomicUpdateDetector   = cfg.detectVarHandleNonAtomicUpdate   ? new VarHandleNonAtomicUpdateDetector()   : null;
        recordMutableComponentLeakDetector = cfg.detectRecordMutableComponentLeak ? new RecordMutableComponentLeakDetector() : null;
        staticInitDeadlockDetector         = cfg.detectStaticInitDeadlock         ? new StaticInitDeadlockDetector()         : null;
        virtualThreadPoolingDetector       = cfg.detectVirtualThreadPooling       ? new VirtualThreadPoolingDetector()       : null;
        platformThreadPerTaskDetector      = cfg.detectPlatformThreadPerTask      ? new PlatformThreadPerTaskDetector()      : null;
        sharedSplittableRandomDetector     = cfg.detectSharedSplittableRandom     ? new SharedSplittableRandomDetector()     : null;
        completableFutureCompletionRaceDetector          = cfg.detectCompletableFutureCompletionRace          ? new CompletableFutureCompletionRaceDetector()          : null;
        completableFutureCancellationPropagationDetector = cfg.detectCompletableFutureCancellationPropagation ? new CompletableFutureCancellationPropagationDetector() : null;
        completableFutureCombinatorMisuseDetector        = cfg.detectCompletableFutureCombinatorMisuse        ? new CompletableFutureCombinatorMisuseDetector()        : null;
        lambdaLostUpdateDetector                         = cfg.detectLambdaLostUpdate                         ? new LambdaLostUpdateDetector()                         : null;
        virtualThreadResourceSaturationDetector          = cfg.detectVirtualThreadResourceSaturation          ? new VirtualThreadResourceSaturationDetector()          : null;
        virtualThreadMonitorSerializationDetector        = cfg.detectVirtualThreadMonitorSerialization        ? new VirtualThreadMonitorSerializationDetector()        : null;
        threadLocalCacheDegradationDetector              = cfg.detectThreadLocalCacheDegradation              ? new ThreadLocalCacheDegradationDetector()              : null;
        scopeJoinerMisuseDetector = cfg.detectScopeJoinerMisuse ? new ScopeJoinerMisuseDetector() : null;
        scopeConfigurationMisuseDetector = cfg.detectScopeConfigurationMisuse ? new ScopeConfigurationMisuseDetector() : null;
        scopeResultEscapeDetector = cfg.detectScopeResultEscape ? new ScopeResultEscapeDetector() : null;
        lazyCollectionMisuseDetector = cfg.detectLazyCollectionMisuse ? new LazyCollectionMisuseDetector() : null;
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
        return new ArrayList<>(analyzeAllNamed().values());
    }

    /** Per-finding grades from the last analysis pass; see {@link #lastGrades()}. */
    private Map<String, List<se.deversity.asynctest.diagnostics.GradedFindings.Grade>> lastGrades = Map.of();

    /**
     * Runs every enabled Phase 2 detector's analysis and returns the reports of any that
     * found issues, keyed by the simple name of the detector that produced each one.
     *
     * <p>A finding's identity must come from its detector, never from its report text. The
     * runner previously derived the name by slicing the report at its first colon, but the
     * detectors that open a report with a severity marker ({@code IssueSeverity.HIGH.format()})
     * all yielded the same key — so distinct findings collapsed into one, and a baselined
     * finding suppressed every later finding of the same severity.
     *
     * @return reports by detector name; never {@code null}
     */
    Map<String, String> analyzeAllNamed() {
        FindingSink out = new FindingSink();

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
        // hasIssues() delegates to hasFairnessIssues(); bind the canonical predicate so this
        // path and the SPI Violation pipeline cannot drift apart.
        ifIssue(readWriteLockMonitor,
                ReadWriteLockMonitor::analyzeFairness,
                ReadWriteLockMonitor.ReadWriteLockReport::hasIssues, out);
        ifIssue(semaphoreMisuseDetector,
                SemaphoreMisuseDetector::analyze,
                SemaphoreMisuseDetector.SemaphoreMisuseReport::hasIssues, out);
        ifIssue(completableFutureExceptionDetector,
                CompletableFutureExceptionDetector::analyze,
                CompletableFutureExceptionDetector.CompletableFutureExceptionReport::hasIssues, out);
        ifIssue(completableFutureCompletionLeakDetector,
                CompletableFutureCompletionLeakDetector::analyze,
                CompletableFutureCompletionLeakDetector.CompletionLeakReport::hasIssues, out);
        // hasIssues() delegates to hasEffectivePinningIssues(), which drops events whose cause
        // no longer pins on the running JDK (synchronized since JEP 491 in 24). Binding
        // hasPinningIssues() here reported those anyway, so that fix never reached the report
        // the user reads - green on every other path. ReportingPathPredicateTest pins this.
        ifIssue(virtualThreadPinningDetector,
                VirtualThreadPinningDetector::analyzePinning,
                VirtualThreadPinningDetector.PinningReport::hasIssues, out);
        ifIssue(threadPoolDeadlockDetector,
                ThreadPoolDeadlockDetector::analyze,
                ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport::hasIssues, out);
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

        // ---- Phase 18: JDK 25/26 GA-era concurrency detectors ----
        ifIssue(lazyConstantMisuseDetector,
                LazyConstantMisuseDetector::analyze,
                LazyConstantMisuseDetector.LazyConstantMisuseReport::hasIssues, out);
        ifIssue(finalFieldMutationDetector,
                FinalFieldMutationDetector::analyze,
                FinalFieldMutationDetector.FinalFieldMutationReport::hasIssues, out);
        ifIssue(sharedKdfDetector,
                SharedKdfDetector::analyze,
                SharedKdfDetector.Report::hasIssues, out);

        ifIssue(latchMisuseDetector,
                LatchMisuseDetector::analyze,
                LatchMisuseDetector.LatchMisuseReport::hasIssues, out);
        ifIssue(executorDeadlockDetector,
                ExecutorDeadlockDetector::analyze,
                ExecutorDeadlockDetector.ExecutorDeadlockReport::hasIssues, out);
        ifIssue(futureBlockingDetector,
                FutureBlockingDetector::analyze,
                FutureBlockingDetector.FutureBlockingReport::hasIssues, out);

        ifIssue(flowPublisherConcurrencyDetector,
                FlowPublisherConcurrencyDetector::analyze,
                FlowPublisherConcurrencyDetector.Report::hasIssues, out);

        // Phase 20: FFM, VarHandle, record and class-initialization hazards
        ifIssue(confinedArenaThreadEscapeDetector,
                ConfinedArenaThreadEscapeDetector::analyze,
                ConfinedArenaThreadEscapeDetector.Report::hasIssues, out);

        ifIssue(sharedMemorySegmentRaceDetector,
                SharedMemorySegmentRaceDetector::analyze,
                SharedMemorySegmentRaceDetector.Report::hasIssues, out);

        ifIssue(varHandleNonAtomicUpdateDetector,
                VarHandleNonAtomicUpdateDetector::analyze,
                VarHandleNonAtomicUpdateDetector.Report::hasIssues, out);

        ifIssue(recordMutableComponentLeakDetector,
                RecordMutableComponentLeakDetector::analyze,
                RecordMutableComponentLeakDetector.Report::hasIssues, out);

        ifIssue(staticInitDeadlockDetector,
                StaticInitDeadlockDetector::analyze,
                StaticInitDeadlockDetector.Report::hasIssues, out);

        ifIssue(virtualThreadPoolingDetector,
                VirtualThreadPoolingDetector::analyze,
                VirtualThreadPoolingDetector.Report::hasIssues, out);

        ifIssue(platformThreadPerTaskDetector,
                PlatformThreadPerTaskDetector::analyze,
                PlatformThreadPerTaskDetector.Report::hasIssues, out);

        ifIssue(sharedSplittableRandomDetector,
                SharedSplittableRandomDetector::analyze,
                SharedSplittableRandomDetector.Report::hasIssues, out);

        ifIssue(completableFutureCompletionRaceDetector,
                CompletableFutureCompletionRaceDetector::analyze,
                CompletableFutureCompletionRaceDetector.Report::hasIssues, out);

        ifIssue(completableFutureCancellationPropagationDetector,
                CompletableFutureCancellationPropagationDetector::analyze,
                CompletableFutureCancellationPropagationDetector.Report::hasIssues, out);

        ifIssue(completableFutureCombinatorMisuseDetector,
                CompletableFutureCombinatorMisuseDetector::analyze,
                CompletableFutureCombinatorMisuseDetector.Report::hasIssues, out);

        ifIssue(lambdaLostUpdateDetector,
                LambdaLostUpdateDetector::analyze,
                LambdaLostUpdateDetector.Report::hasIssues, out);

        ifIssue(virtualThreadResourceSaturationDetector,
                VirtualThreadResourceSaturationDetector::analyze,
                VirtualThreadResourceSaturationDetector.Report::hasIssues, out);

        ifIssue(virtualThreadMonitorSerializationDetector,
                VirtualThreadMonitorSerializationDetector::analyze,
                VirtualThreadMonitorSerializationDetector.Report::hasIssues, out);

        ifIssue(threadLocalCacheDegradationDetector,
                ThreadLocalCacheDegradationDetector::analyze,
                ThreadLocalCacheDegradationDetector.Report::hasIssues, out);

        ifIssue(scopeJoinerMisuseDetector,
                ScopeJoinerMisuseDetector::analyze,
                ScopeJoinerMisuseDetector.Report::hasIssues, out);

        ifIssue(scopeConfigurationMisuseDetector,
                ScopeConfigurationMisuseDetector::analyze,
                ScopeConfigurationMisuseDetector.Report::hasIssues, out);

        ifIssue(scopeResultEscapeDetector,
                ScopeResultEscapeDetector::analyze,
                ScopeResultEscapeDetector.Report::hasIssues, out);

        ifIssue(lazyCollectionMisuseDetector,
                LazyCollectionMisuseDetector::analyze,
                LazyCollectionMisuseDetector.Report::hasIssues, out);

        lastGrades = out.grades();
        return out.reports();
    }

    /**
     * {@return the per-finding grades from the most recent {@link #analyzeAllNamed()} pass}
     *
     * <p>Empty for every detector whose report does not implement
     * {@link se.deversity.asynctest.diagnostics.GradedFindings}, which is most of them; the gate
     * falls back to the detector's own tier and severity for those.
     */
    Map<String, List<se.deversity.asynctest.diagnostics.GradedFindings.Grade>> lastGrades() {
        return lastGrades;
    }

    // ---- Helper ----

    /**
     * If {@code detector} is non-null and the report from {@code analyze} has issues,
     * records the report's {@code toString()} in {@code out} under the detector's simple
     * class name.
     *
     * <p>The name is taken from the detector object rather than parsed out of the report,
     * so two detectors whose reports happen to open with the same prose (e.g. the same
     * severity marker) stay distinct findings.
     */
    static <D, R> void ifIssue(@Nullable D detector,
                               Function<D, R> analyze,
                               Function<R, Boolean> hasIssues,
                               FindingSink out) {
        if (detector == null) return;
        String name = detector.getClass().getSimpleName();
        try {
            R report = analyze.apply(detector);
            if (Boolean.TRUE.equals(hasIssues.apply(report))) {
                out.add(name, report.toString(),
                        report instanceof se.deversity.asynctest.diagnostics.GradedFindings graded
                                ? graded.grades() : null);
            }
        } catch (RuntimeException | StackOverflowError e) {
            // Contain the failure: analyzeAllNamed() chains ~100 of these, so letting one
            // detector's exception escape would discard every finding collected so far and
            // skip every detector after it. A broken detector reports nothing; the rest of
            // the sweep still reports. Detectors accumulate state from N×M user threads, and
            // third-party ones arrive via the public SPI, so this is a live hazard.
            //
            // Containment also means a broken detector is invisible in a passing build, which
            // is how five of them shipped. DetectorFailurePolicy keeps the containment for
            // consumers and turns it into a failure under this project's own test config.
            DetectorFailurePolicy.detectorFailed(name, e);
        }
    }
}
