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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

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
    focus = "Each new detector requires exactly three steps in this class: (1) a final field declaration, (2) its factory-table row in the constructor, field = create(DetectorType.TYPE, Detector::new), keyed on the type and never on a config flag (#916), (3) an analyzeAll() call in the correct phase block. All three steps must be added together.",
    avoids = "partial patterns — a field without construction or analysis silently skips detection"
)
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "No locks: every detector field is final and assigned in the constructor, before ConcurrencyRunner publishes the registry to its workers, so every worker of a run reads the same instances and each detector carries its own thread safety. The last* maps are written only by analyzeAllNamed(), which the runner calls on its own thread after the workers have quiesced.")
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

    /** The run's selection, read by {@link #create} while the constructor runs. */
    private final Set<DetectorType> enabled;

    /**
     * The factory table's output: every detector this registry built, keyed by its type (#916).
     * Written only by {@link #create} during construction, so it is frozen with the final fields.
     */
    private final Map<DetectorType, Object> instances = new EnumMap<>(DetectorType.class);

    /**
     * Instantiates the detectors {@link AsyncTestConfig#enabledDetectors()} names, one per type.
     * Every other field stays {@code null} and costs nothing during the run.
     *
     * <p>Each line below is one row of the factory table: a {@link DetectorType} and the
     * constructor that builds it. Construction is keyed on the type, not on a config flag of its
     * own, so there is nothing per detector left to pair with the wrong flag, and
     * {@code DetectorRegistryFactoryTableTest} compares what was built with what was asked for.
     */
    DetectorRegistry(AsyncTestConfig cfg) {
        enabled = cfg.enabledDetectors();
        deadlockDetector           = create(DetectorType.DEADLOCKS, DeadlockDetector::new);
        visibilityMonitor          = create(DetectorType.VISIBILITY, VisibilityMonitor::new);
        livelockDetector           = create(DetectorType.LIVELOCKS, LivelockDetector::new);
        falseSharingDetector       = create(DetectorType.FALSE_SHARING, FalseSharingDetector::new);
        wakeupDetector             = create(DetectorType.WAKEUP_ISSUES, WakeupDetector::new);
        constructorSafetyValidator = create(DetectorType.CONSTRUCTOR_SAFETY, ConstructorSafetyValidator::new);
        abaProblemDetector         = create(DetectorType.ABA_PROBLEM, ABAProblemDetector::new);
        lockOrderValidator         = create(DetectorType.LOCK_ORDER, LockOrderValidator::new);
        synchronizerMonitor        = create(DetectorType.SYNCHRONIZERS, SynchronizerMonitor::new);
        threadPoolMonitor          = create(DetectorType.THREAD_POOL, ThreadPoolMonitor::new);
        memoryOrderingMonitor      = create(DetectorType.MEMORY_ORDERING, MemoryOrderingMonitor::new);
        pipelineMonitor            = create(DetectorType.ASYNC_PIPELINE, PipelineMonitor::new);
        readWriteLockMonitor       = create(DetectorType.READ_WRITE_LOCK_FAIRNESS, ReadWriteLockMonitor::new);
        semaphoreMisuseDetector    = create(DetectorType.SEMAPHORE, SemaphoreMisuseDetector::new);
        completableFutureExceptionDetector = create(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, CompletableFutureExceptionDetector::new);
        completableFutureCompletionLeakDetector = create(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, CompletableFutureCompletionLeakDetector::new);
        virtualThreadPinningDetector = create(DetectorType.VIRTUAL_THREAD_PINNING, VirtualThreadPinningDetector::new);
        // The same defect SleepInLockDetector had below: recordBlockingOperation and
        // recordSynchronizedBlock both return early on a monitoring flag that defaults to false,
        // and nothing in main code turned it on - the javadoc, the fixture and example 92 each
        // called startMonitoring() themselves, so detectVirtualThreadPinning = true on its own
        // produced a clean report on code that pins all day (#501).
        if (virtualThreadPinningDetector != null) {
            virtualThreadPinningDetector.startMonitoring();
        }
        threadPoolDeadlockDetector = create(DetectorType.THREAD_POOL_DEADLOCK, ThreadPoolDeadlockDetector::new);
        concurrentModificationDetector = create(DetectorType.CONCURRENT_MODIFICATIONS, ConcurrentModificationDetector::new);
        lockLeakDetector           = create(DetectorType.LOCK_LEAKS, LockLeakDetector::new);
        sharedRandomDetector       = create(DetectorType.SHARED_RANDOM, SharedRandomDetector::new);
        blockingQueueDetector      = create(DetectorType.BLOCKING_QUEUE, BlockingQueueDetector::new);
        conditionVariableDetector  = create(DetectorType.CONDITION_VARIABLES, ConditionVariableDetector::new);
        simpleDateFormatDetector   = create(DetectorType.SIMPLE_DATE_FORMAT, SimpleDateFormatDetector::new);
        parallelStreamDetector     = create(DetectorType.PARALLEL_STREAMS, ParallelStreamDetector::new);
        resourceLeakDetector       = create(DetectorType.RESOURCE_LEAKS, ResourceLeakDetector::new);
        countDownLatchDetector     = create(DetectorType.COUNTDOWN_LATCH, CountDownLatchDetector::new);
        cyclicBarrierDetector      = create(DetectorType.CYCLIC_BARRIER, CyclicBarrierDetector::new);
        reentrantLockDetector      = create(DetectorType.REENTRANT_LOCK, ReentrantLockDetector::new);
        volatileArrayDetector      = create(DetectorType.VOLATILE_ARRAY, VolatileArrayDetector::new);
        doubleCheckedLockingDetector = create(DetectorType.DOUBLE_CHECKED_LOCKING, DoubleCheckedLockingDetector::new);
        waitTimeoutDetector        = create(DetectorType.WAIT_TIMEOUT, WaitTimeoutDetector::new);
        lockContentionDetector     = create(DetectorType.LOCK_CONTENTION, LockContentionDetector::new);
        synchronizedNonFinalDetector = create(DetectorType.SYNCHRONIZED_NON_FINAL, SynchronizedNonFinalDetector::new);
        missedSignalDetector       = create(DetectorType.MISSED_SIGNAL, MissedSignalDetector::new);
        lazyInitRaceDetector       = create(DetectorType.LAZY_INIT_RACE, LazyInitRaceDetector::new);
        phaserDetector             = create(DetectorType.PHASER, PhaserDetector::new);
        stampedLockDetector        = create(DetectorType.STAMPED_LOCK, StampedLockDetector::new);
        exchangerDetector          = create(DetectorType.EXCHANGER, ExchangerDetector::new);
        scheduledExecutorDetector  = create(DetectorType.SCHEDULED_EXECUTOR, ScheduledExecutorDetector::new);
        forkJoinPoolDetector       = create(DetectorType.FORK_JOIN_POOL, ForkJoinPoolDetector::new);
        threadFactoryDetector      = create(DetectorType.THREAD_FACTORY, ThreadFactoryDetector::new);
        raceConditionDetector      = create(DetectorType.RACE_CONDITIONS, RaceConditionDetector::new);
        threadLocalMonitor         = create(DetectorType.THREAD_LOCAL_LEAKS, ThreadLocalMonitor::new);
        busyWaitDetector           = create(DetectorType.BUSY_WAITING, BusyWaitDetector::new);
        atomicityValidator         = create(DetectorType.ATOMICITY_VIOLATIONS, AtomicityValidator::new);
        interruptMonitor           = create(DetectorType.INTERRUPT_MISHANDLING, InterruptMonitor::new);
        threadLeakDetector         = create(DetectorType.THREAD_LEAKS, ThreadLeakDetector::new);
        sleepInLockDetector        = create(DetectorType.SLEEP_IN_LOCK, SleepInLockDetector::new);
        // Without this the detector is inert: every recordSleep returns early on a monitoring
        // flag that defaults to false, and nothing in main code had ever turned it on - only its
        // own unit test did. Constructing it and never starting it meant detectSleepInLock=true
        // produced a clean report on code that sleeps under a lock all day.
        if (sleepInLockDetector != null) {
            sleepInLockDetector.startMonitoring();
        }
        unboundedQueueDetector     = create(DetectorType.UNBOUNDED_QUEUE, UnboundedQueueDetector::new);
        threadStarvationDetector   = create(DetectorType.THREAD_STARVATION, ThreadStarvationDetector::new);
        calendarDetector           = create(DetectorType.CALENDAR, CalendarDetector::new);
        sharedCollectionDetector   = create(DetectorType.SHARED_COLLECTIONS, SharedCollectionDetector::new);
        timerDetector              = create(DetectorType.TIMER, TimerDetector::new);
        copyOnWriteCollectionDetector = create(DetectorType.COPY_ON_WRITE_COLLECTIONS, CopyOnWriteCollectionDetector::new);
        stringBuilderDetector      = create(DetectorType.STRING_BUILDER, StringBuilderDetector::new);
        structuredConcurrencyMisuseDetector = create(DetectorType.STRUCTURED_CONCURRENCY, StructuredConcurrencyMisuseDetector::new);
        virtualThreadContextLeakDetector = create(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, VirtualThreadContextLeakDetector::new);
        scopedValueMisuseDetector = create(DetectorType.SCOPED_VALUE, ScopedValueMisuseDetector::new);
        virtualThreadCpuBoundTaskDetector = create(DetectorType.VIRTUAL_THREAD_CPU_BOUND, VirtualThreadCpuBoundTaskDetector::new);
        virtualThreadCarrierExhaustionDetector = create(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION, VirtualThreadCarrierExhaustionDetector::new);

        // ---- Phase 7: High-Level Concurrency Patterns ----
        httpClientConcurrencyDetector = create(DetectorType.HTTP_CLIENT, HttpClientConcurrencyDetector::new);
        streamClosingDetector = create(DetectorType.STREAM_CLOSING, StreamClosingDetector::new);
        cacheConcurrencyDetector = create(DetectorType.CACHE_CONCURRENCY, CacheConcurrencyDetector::new);
        completableFutureChainDetector = create(DetectorType.COMPLETABLEFUTURE_CHAIN, CompletableFutureChainDetector::new);

        // ---- Phase 8: Lifecycle & Structural Correctness ----
        executorShutdownDetector = create(DetectorType.EXECUTOR_SHUTDOWN, ExecutorShutdownDetector::new);
        mutableMapKeyDetector = create(DetectorType.MUTABLE_MAP_KEY, MutableMapKeyDetector::new);
        nestedMonitorLockoutDetector = create(DetectorType.NESTED_MONITOR_LOCKOUT, NestedMonitorLockoutDetector::new);
        lockDowngradeDetector = create(DetectorType.LOCK_DOWNGRADE, LockDowngradeDetector::new);
        inheritableThreadLocalMisuseDetector = create(DetectorType.INHERITABLE_THREAD_LOCAL, InheritableThreadLocalMisuseDetector::new);

        // ---- Phase 10: API Traps & Subtle Concurrency Bugs ----
        threadLocalContaminationDetector = create(DetectorType.THREAD_LOCAL_CONTAMINATION, ThreadLocalContaminationDetector::new);
        atomicNonAtomicUpdateDetector = create(DetectorType.ATOMIC_NON_ATOMIC_UPDATE, AtomicNonAtomicUpdateDetector::new);
        synchronizedCollectionIterationDetector = create(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, SynchronizedCollectionIterationDetector::new);
        sharedFormatterDetector = create(DetectorType.SHARED_FORMATTER, SharedFormatterDetector::new);
        concurrentMapComputeRecursionDetector = create(DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, ConcurrentMapComputeRecursionDetector::new);
        synchronizedOnLiteralDetector = create(DetectorType.SYNCHRONIZED_ON_LITERAL, SynchronizedOnLiteralDetector::new);
        publicLockExposureDetector = create(DetectorType.PUBLIC_LOCK_EXPOSURE, PublicLockExposureDetector::new);
        forkJoinTaskBlockingDetector = create(DetectorType.FORK_JOIN_TASK_BLOCKING, ForkJoinTaskBlockingDetector::new);
        optimisticReadValidationDetector = create(DetectorType.OPTIMISTIC_READ_VALIDATION, OptimisticReadValidationDetector::new);
        cfCommonPoolBlockingDetector = create(DetectorType.CF_COMMON_POOL_BLOCKING, CompletableFutureCommonPoolBlockingDetector::new);

        // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----
        sharedMatcherDetector       = create(DetectorType.SHARED_MATCHER, SharedMatcherDetector::new);
        sharedDecimalFormatDetector = create(DetectorType.SHARED_DECIMAL_FORMAT, SharedDecimalFormatDetector::new);
        weakReferenceRaceDetector   = create(DetectorType.WEAK_REFERENCE_RACE, WeakReferenceRaceDetector::new);
        statefulLambdaDetector      = create(DetectorType.STATEFUL_LAMBDA, StatefulLambdaDetector::new);
        sharedMessageDigestDetector = create(DetectorType.SHARED_MESSAGE_DIGEST, SharedMessageDigestDetector::new);

        // ---- Phase 12: Operational & Hygiene Concurrency Issues ----
        interruptSwallowingDetector      = create(DetectorType.INTERRUPT_SWALLOWING, InterruptSwallowingDetector::new);
        mdcContextLeakDetector           = create(DetectorType.MDC_CONTEXT_LEAK, MdcContextLeakDetector::new);
        systemPropertyMutationDetector   = create(DetectorType.SYSTEM_PROPERTY_MUTATION, SystemPropertyMutationDetector::new);
        futureIgnoredDetector            = create(DetectorType.FUTURE_IGNORED, FutureIgnoredDetector::new);
        explicitGcDetector               = create(DetectorType.EXPLICIT_GC, ExplicitGcDetector::new);
        deprecatedThreadApiDetector      = create(DetectorType.DEPRECATED_THREAD_API, DeprecatedThreadApiDetector::new);
        sharedXmlParserDetector          = create(DetectorType.SHARED_XML_PARSER, SharedXmlParserDetector::new);
        boxedPrimitiveLockDetector       = create(DetectorType.BOXED_PRIMITIVE_LOCK, BoxedPrimitiveLockDetector::new);
        sharedTimeZoneDetector           = create(DetectorType.SHARED_TIMEZONE, SharedTimeZoneDetector::new);
        uncaughtExceptionHandlerDetector = create(DetectorType.UNCAUGHT_EXCEPTION_HANDLER, UncaughtExceptionHandlerDetector::new);

        // ---- Phase 13: Additional concurrency-bug categories (1.0.0+) ----
        daemonThreadHygieneDetector  = create(DetectorType.DAEMON_THREAD_HYGIENE, DaemonThreadHygieneDetector::new);
        notifyWithoutMonitorDetector = create(DetectorType.NOTIFY_WITHOUT_MONITOR, NotifyWithoutMonitorDetector::new);
        sharedSecureRandomDetector   = create(DetectorType.SHARED_SECURE_RANDOM, SharedSecureRandomDetector::new);
        weakHashMapSharedDetector    = create(DetectorType.WEAK_HASH_MAP_SHARED, WeakHashMapSharedDetector::new);
        jdbcConnectionSharedDetector = create(DetectorType.JDBC_CONNECTION_SHARED, JdbcConnectionSharedDetector::new);

        // ---- Phase 14: Additional thread-unsafe primitives & publication hazards (1.7.0+) ----
        sharedStatefulCryptoDetector         = create(DetectorType.SHARED_STATEFUL_CRYPTO, SharedStatefulCryptoDetector::new);
        nonAtomicConcurrentMapUpdateDetector = create(DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, NonAtomicConcurrentMapUpdateDetector::new);
        sharedDeflaterDetector               = create(DetectorType.SHARED_DEFLATER, SharedDeflaterDetector::new);
        thisEscapeDetector                   = create(DetectorType.THIS_ESCAPE, ThisEscapeDetector::new);
        threadLocalRandomMisuseDetector      = create(DetectorType.THREAD_LOCAL_RANDOM_MISUSE, ThreadLocalRandomMisuseDetector::new);
        // Phase 15
        completableFutureObtrudeDetector = create(DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE, CompletableFutureObtrudeDetector::new);
        spuriousWakeupHazardDetector     = create(DetectorType.SPURIOUS_WAKEUP_HAZARD, SpuriousWakeupDetector::new);
        lockUpgradeDeadlockDetector      = create(DetectorType.LOCK_UPGRADE_DEADLOCK, LockUpgradeDeadlockDetector::new);

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
        tryLockMisuseDetector            = create(DetectorType.TRY_LOCK_MISUSE, TryLockMisuseDetector::new);
        cfBlockingCallbackDetector       = create(DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK, CompletableFutureBlockingCallbackDetector::new);
        // ---- Phase 16: JDK 25/26 preview-era concurrency detectors ----
        stableValueMisuseDetector         = create(DetectorType.STABLE_VALUE_MISUSE, StableValueMisuseDetector::new);
        structuredTaskScopeMisuseDetector = create(DetectorType.STRUCTURED_TASK_SCOPE_MISUSE, StructuredTaskScopeMisuseDetector::new);
        gathererConcurrencyMisuseDetector = create(DetectorType.GATHERER_CONCURRENCY_MISUSE, GathererConcurrencyMisuseDetector::new);
        // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----
        sharedByteBufferDetector         = create(DetectorType.SHARED_BYTE_BUFFER, SharedByteBufferDetector::new);
        sharedCharsetCoderDetector       = create(DetectorType.SHARED_CHARSET_CODER, SharedCharsetCoderDetector::new);
        sharedChecksumDetector           = create(DetectorType.SHARED_CHECKSUM, SharedChecksumDetector::new);
        fileChannelPositionRaceDetector  = create(DetectorType.FILE_CHANNEL_POSITION_RACE, FileChannelPositionRaceDetector::new);
        sharedIteratorDetector           = create(DetectorType.SHARED_ITERATOR, SharedIteratorDetector::new);
        highContentionAtomicDetector     = create(DetectorType.HIGH_CONTENTION_ATOMIC, HighContentionAtomicDetector::new);
        sharedJsonMapperReconfigDetector = create(DetectorType.SHARED_JSON_MAPPER_RECONFIG, SharedJsonMapperReconfigDetector::new);
        // ---- Phase 18: JDK 25/26 GA-era concurrency detectors ----
        lazyConstantMisuseDetector       = create(DetectorType.LAZY_CONSTANT_MISUSE, LazyConstantMisuseDetector::new);
        finalFieldMutationDetector       = create(DetectorType.FINAL_FIELD_MUTATION, FinalFieldMutationDetector::new);
        sharedKdfDetector                = create(DetectorType.SHARED_KDF, SharedKdfDetector::new);
        latchMisuseDetector              = create(DetectorType.LATCH_MISUSE, LatchMisuseDetector::new);
        executorDeadlockDetector         = create(DetectorType.EXECUTOR_DEADLOCK, ExecutorDeadlockDetector::new);
        futureBlockingDetector           = create(DetectorType.FUTURE_BLOCKING, FutureBlockingDetector::new);
        flowPublisherConcurrencyDetector = create(DetectorType.FLOW_PUBLISHER_CONCURRENCY, FlowPublisherConcurrencyDetector::new);
        confinedArenaThreadEscapeDetector  = create(DetectorType.CONFINED_ARENA_THREAD_ESCAPE, ConfinedArenaThreadEscapeDetector::new);
        sharedMemorySegmentRaceDetector    = create(DetectorType.SHARED_MEMORY_SEGMENT_RACE, SharedMemorySegmentRaceDetector::new);
        varHandleNonAtomicUpdateDetector   = create(DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE, VarHandleNonAtomicUpdateDetector::new);
        recordMutableComponentLeakDetector = create(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK, RecordMutableComponentLeakDetector::new);
        staticInitDeadlockDetector         = create(DetectorType.STATIC_INIT_DEADLOCK, StaticInitDeadlockDetector::new);
        virtualThreadPoolingDetector       = create(DetectorType.VIRTUAL_THREAD_POOLING, VirtualThreadPoolingDetector::new);
        platformThreadPerTaskDetector      = create(DetectorType.PLATFORM_THREAD_PER_TASK, PlatformThreadPerTaskDetector::new);
        sharedSplittableRandomDetector     = create(DetectorType.SHARED_SPLITTABLE_RANDOM, SharedSplittableRandomDetector::new);
        completableFutureCompletionRaceDetector          = create(DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE, CompletableFutureCompletionRaceDetector::new);
        completableFutureCancellationPropagationDetector = create(DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION, CompletableFutureCancellationPropagationDetector::new);
        completableFutureCombinatorMisuseDetector        = create(DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE, CompletableFutureCombinatorMisuseDetector::new);
        lambdaLostUpdateDetector                         = create(DetectorType.LAMBDA_LOST_UPDATE, LambdaLostUpdateDetector::new);
        virtualThreadResourceSaturationDetector          = create(DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION, VirtualThreadResourceSaturationDetector::new);
        virtualThreadMonitorSerializationDetector        = create(DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION, VirtualThreadMonitorSerializationDetector::new);
        threadLocalCacheDegradationDetector              = create(DetectorType.THREAD_LOCAL_CACHE_DEGRADATION, ThreadLocalCacheDegradationDetector::new);
        scopeJoinerMisuseDetector = create(DetectorType.SCOPE_JOINER_MISUSE, ScopeJoinerMisuseDetector::new);
        scopeConfigurationMisuseDetector = create(DetectorType.SCOPE_CONFIGURATION_MISUSE, ScopeConfigurationMisuseDetector::new);
        scopeResultEscapeDetector = create(DetectorType.SCOPE_RESULT_ESCAPE, ScopeResultEscapeDetector::new);
        lazyCollectionMisuseDetector = create(DetectorType.LAZY_COLLECTION_MISUSE, LazyCollectionMisuseDetector::new);
    }

    /**
     * {@return a fresh detector from {@code factory} when the run enables {@code type}, otherwise
     * {@code null}}
     *
     * @throws IllegalStateException when a second row names the same type: two factories for one
     *                               type would leave one of the two fields unreachable by excludes
     */
    private <T> @Nullable T create(DetectorType type, Supplier<T> factory) {
        if (!enabled.contains(type)) {
            return null;
        }
        T detector = factory.get();
        if (instances.putIfAbsent(type, detector) != null) {
            throw new IllegalStateException(type + " has two rows in DetectorRegistry's factory table");
        }
        return detector;
    }

    /** {@return every detector this registry built, keyed by type; for tests} */
    Map<DetectorType, Object> instances() {
        return Collections.unmodifiableMap(instances);
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
                ConditionVariableDetector.ConditionVariableReport::hasIssues,
                ConditionVariableDetector.ConditionVariableReport::notes, out);
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
                ReentrantLockDetector.ReentrantLockReport::hasIssues,
                ReentrantLockDetector.ReentrantLockReport::notes, out);
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
                SynchronizedNonFinalDetector.SynchronizedNonFinalReport::hasIssues,
                SynchronizedNonFinalDetector.SynchronizedNonFinalReport::notes, out);
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
                ExchangerDetector.ExchangerReport::hasIssues,
                ExchangerDetector.ExchangerReport::notes, out);
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
        lastSeverities = out.severities();
        lastMessages = out.messages();
        lastNotes = out.notes();
        return out.reports();
    }

    /** Notes of reports with no finding from the last analysis pass; see {@link #lastNotes()}. */
    private Map<String, List<String>> lastNotes = Map.of();

    /**
     * {@return the notes of every report that had no finding in the most recent
     * {@link #analyzeAllNamed()} pass, keyed by detector}
     *
     * <p>A report is printed only when it has a finding, so a note in one that has none never
     * reached the user (#816). A note in a report that has a finding is printed with it and is
     * not repeated here.
     */
    Map<String, List<String>> lastNotes() {
        return lastNotes;
    }

    /** Structured severities from the last analysis pass; see {@link #lastSeverities()}. */
    private Map<String, se.deversity.asynctest.diagnostics.IssueSeverity> lastSeverities = Map.of();

    /**
     * {@return the most severe structured severity per detector from the most recent
     * {@link #analyzeAllNamed()} pass}
     *
     * <p>Present only for detectors whose report keeps its findings as
     * {@link se.deversity.asynctest.report.Violation}s as well as text; see
     * {@link se.deversity.asynctest.diagnostics.DetectorDefaultSeverity#structuredIn}.
     */
    Map<String, se.deversity.asynctest.diagnostics.IssueSeverity> lastSeverities() {
        return lastSeverities;
    }

    /** Structured finding messages from the last analysis pass; see {@link #lastMessages()}. */
    private Map<String, List<String>> lastMessages = Map.of();

    /**
     * {@return the messages of each report's structured findings from the most recent
     * {@link #analyzeAllNamed()} pass, keyed by detector; absent where a report keeps none}
     */
    Map<String, List<String>> lastMessages() {
        return lastMessages;
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
        ifIssue(detector, analyze, hasIssues, null, out);
    }

    /**
     * As {@link #ifIssue(Object, Function, Function, FindingSink)}, and when the report has no
     * issues, records the notes {@code notes} reads from it: things the detector wants the
     * caller to know that are not findings, which would otherwise never be seen, because only a
     * report with a finding is printed (#816).
     */
    static <D, R> void ifIssue(@Nullable D detector,
                               Function<D, R> analyze,
                               Function<R, Boolean> hasIssues,
                               @Nullable Function<R, List<String>> notes,
                               FindingSink out) {
        if (detector == null) return;
        String name = detector.getClass().getSimpleName();
        try {
            R report = analyze.apply(detector);
            if (Boolean.TRUE.equals(hasIssues.apply(report))) {
                List<se.deversity.asynctest.report.Violation> findings =
                        se.deversity.asynctest.diagnostics.DetectorDefaultSeverity.structuredFindingsIn(report);
                se.deversity.asynctest.diagnostics.IssueSeverity structured = null;
                List<String> messages = new ArrayList<>(findings.size());
                for (se.deversity.asynctest.report.Violation finding : findings) {
                    messages.add(finding.message());
                    if (structured == null || finding.severity().compareTo(structured) < 0) {
                        structured = finding.severity();
                    }
                }
                // A grade above the detector's evidence cap is lowered here, the one place grades
                // enter the sink, so the failOn gate, the banner and findingGrades() all read the
                // tier the evidence can carry rather than the one the report named.
                out.add(name, report.toString(),
                        report instanceof se.deversity.asynctest.diagnostics.GradedFindings graded
                                ? se.deversity.asynctest.diagnostics.DetectorTrust.clampToCap(name, graded.grades())
                                : null,
                        structured, messages);
                if (structured == null) {
                    // One check per report, never per access: a structured report whose list
                    // stayed empty on this path fails this build's tests, and nothing else (#802).
                    DetectorFailurePolicy.structuredFindingsMissing(name, report);
                }
            } else if (notes != null) {
                out.note(name, notes.apply(report));
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
