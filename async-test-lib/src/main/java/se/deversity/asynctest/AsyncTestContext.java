package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

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
import se.deversity.asynctest.diagnostics.DeadlockDetector;
import se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector;
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
import se.deversity.asynctest.diagnostics.HeldLocks;
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
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.asynctest.report.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
@AICore(
    sensitivity = "Critical",
    note = "ThreadLocal install/uninstall must always be symmetric. A leak propagates stale detector state across test invocations and causes false positives or missed detections."
)
@AIAudit(checkFor = {"Thread Safety issues"})
@AIThreadSafe(strategy = AIThreadSafe.Strategy.THREAD_LOCAL, note = "CURRENT ThreadLocal maintains context per active test thread symmetrically.")
@AIPublicAPI
@API(status = Status.STABLE)
public final class AsyncTestContext {

    private static final ThreadLocal<AsyncTestContext> CURRENT = new ThreadLocal<>();

    /** Holds detector instances; extracted to keep this class small. */
    private final DetectorRegistry registry;

    /**
     * Third-party detectors contributed through the public {@link se.deversity.asynctest.spi.Detector}
     * SPI, discovered once per context via {@code ServiceLoader}.
     *
     * <p>Built-in bridge factories are excluded (see
     * {@link se.deversity.asynctest.spi.DetectorRegistry#buildExternal}); the built-in detectors
     * run through {@link #registry} above, which owns the instances user code records into.
     * Without this field the SPI was inert at runtime: nothing on the execution path ever built
     * an SPI registry, so a user-supplied detector was discovered by nobody, never received its
     * lifecycle callbacks, and its violations reached neither the reports nor the failOn gate.
     *
     * <p>Effectively immutable after construction; the registry itself is only read afterwards.
     */
    private final se.deversity.asynctest.spi.DetectorRegistry externalDetectors;

    /**
     * Guards the single {@code onTestEnd()} sweep over {@link #externalDetectors}. Analysis runs on
     * the runner thread only (see {@link #analyzeAllNamed()}), but the flag is atomic so that a
     * stray call from a still-draining worker thread cannot fire the hook twice.
     */
    private final java.util.concurrent.atomic.AtomicBoolean externalTestEndFired =
            new java.util.concurrent.atomic.AtomicBoolean();

    // ---- Package-private field accessors for DetectorRegistry (used by tests) ----
    // These are forwarded to the registry so existing test code that accesses
    // ctx.lockLeakDetector etc. continues to work without modification.

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
    final @Nullable SemaphoreMisuseDetector    semaphoreMisuseDetector;
    final @Nullable CompletableFutureExceptionDetector completableFutureExceptionDetector;
    final @Nullable CompletableFutureCompletionLeakDetector completableFutureCompletionLeakDetector;
    final @Nullable VirtualThreadPinningDetector virtualThreadPinningDetector;
    final @Nullable ThreadPoolDeadlockDetector threadPoolDeadlockDetector;
    final @Nullable ConcurrentModificationDetector concurrentModificationDetector;
    final @Nullable LockLeakDetector lockLeakDetector;
    final @Nullable SharedRandomDetector sharedRandomDetector;
    final @Nullable BlockingQueueDetector blockingQueueDetector;
    final @Nullable ConditionVariableDetector conditionVariableDetector;
    final @Nullable SimpleDateFormatDetector simpleDateFormatDetector;
    final @Nullable ParallelStreamDetector parallelStreamDetector;
    final @Nullable ResourceLeakDetector resourceLeakDetector;
    final @Nullable CountDownLatchDetector countDownLatchDetector;
    final @Nullable CyclicBarrierDetector cyclicBarrierDetector;
    final @Nullable ReentrantLockDetector reentrantLockDetector;
    final @Nullable VolatileArrayDetector volatileArrayDetector;
    final @Nullable DoubleCheckedLockingDetector doubleCheckedLockingDetector;
    final @Nullable WaitTimeoutDetector waitTimeoutDetector;
    final @Nullable LockContentionDetector lockContentionDetector;
    final @Nullable SynchronizedNonFinalDetector synchronizedNonFinalDetector;
    final @Nullable MissedSignalDetector missedSignalDetector;
    final @Nullable LazyInitRaceDetector lazyInitRaceDetector;
    final @Nullable PhaserDetector phaserDetector;
    final @Nullable StampedLockDetector stampedLockDetector;
    final @Nullable ExchangerDetector exchangerDetector;
    final @Nullable ScheduledExecutorDetector scheduledExecutorDetector;
    final @Nullable ForkJoinPoolDetector forkJoinPoolDetector;
    final @Nullable ThreadFactoryDetector threadFactoryDetector;
    final @Nullable ThreadLeakDetector threadLeakDetector;
    final @Nullable SleepInLockDetector sleepInLockDetector;
    final @Nullable UnboundedQueueDetector unboundedQueueDetector;
    final @Nullable ThreadStarvationDetector threadStarvationDetector;
    final @Nullable CalendarDetector calendarDetector;
    final @Nullable SharedCollectionDetector sharedCollectionDetector;
    final @Nullable TimerDetector timerDetector;
    final @Nullable CopyOnWriteCollectionDetector copyOnWriteCollectionDetector;
    final @Nullable StringBuilderDetector stringBuilderDetector;
    final @Nullable StructuredConcurrencyMisuseDetector    structuredConcurrencyMisuseDetector;
    final @Nullable VirtualThreadContextLeakDetector       virtualThreadContextLeakDetector;
    final @Nullable ScopedValueMisuseDetector              scopedValueMisuseDetector;
    final @Nullable VirtualThreadCpuBoundTaskDetector      virtualThreadCpuBoundTaskDetector;
    final @Nullable VirtualThreadCarrierExhaustionDetector virtualThreadCarrierExhaustionDetector;
    final @Nullable HttpClientConcurrencyDetector          httpClientConcurrencyDetector;
    final @Nullable StreamClosingDetector               streamClosingDetector;
    final @Nullable CacheConcurrencyDetector            cacheConcurrencyDetector;
    final @Nullable CompletableFutureChainDetector      completableFutureChainDetector;
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

    // ---- Agent-telemetry bridge target (1.7.0+) ----
    // Exposed via atomicityValidator() so se.deversity.asynctest.telemetry.TelemetryBridge
    // can route drained agent field-access events into the live per-test detector.
    final @Nullable AtomicityValidator                    atomicityValidator;
    /**
     * Creates a AsyncTestContext.
     *
     * @param cfg the resolved configuration deciding which detectors this context installs
     */
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
        // Phase 13
        daemonThreadHygieneDetector            = registry.daemonThreadHygieneDetector;
        notifyWithoutMonitorDetector           = registry.notifyWithoutMonitorDetector;
        sharedSecureRandomDetector             = registry.sharedSecureRandomDetector;
        weakHashMapSharedDetector              = registry.weakHashMapSharedDetector;
        jdbcConnectionSharedDetector           = registry.jdbcConnectionSharedDetector;
        // Phase 14
        sharedStatefulCryptoDetector           = registry.sharedStatefulCryptoDetector;
        nonAtomicConcurrentMapUpdateDetector   = registry.nonAtomicConcurrentMapUpdateDetector;
        sharedDeflaterDetector                 = registry.sharedDeflaterDetector;
        thisEscapeDetector                     = registry.thisEscapeDetector;
        threadLocalRandomMisuseDetector        = registry.threadLocalRandomMisuseDetector;
        // Phase 15
        completableFutureObtrudeDetector       = registry.completableFutureObtrudeDetector;
        spuriousWakeupHazardDetector           = registry.spuriousWakeupHazardDetector;
        lockUpgradeDeadlockDetector            = registry.lockUpgradeDeadlockDetector;
        tryLockMisuseDetector                  = registry.tryLockMisuseDetector;
        cfBlockingCallbackDetector             = registry.cfBlockingCallbackDetector;
        // Phase 16 (JDK 25/26)
        stableValueMisuseDetector              = registry.stableValueMisuseDetector;
        structuredTaskScopeMisuseDetector      = registry.structuredTaskScopeMisuseDetector;
        gathererConcurrencyMisuseDetector      = registry.gathererConcurrencyMisuseDetector;
        // Phase 17
        sharedByteBufferDetector               = registry.sharedByteBufferDetector;
        sharedCharsetCoderDetector             = registry.sharedCharsetCoderDetector;
        sharedChecksumDetector                 = registry.sharedChecksumDetector;
        fileChannelPositionRaceDetector        = registry.fileChannelPositionRaceDetector;
        sharedIteratorDetector                 = registry.sharedIteratorDetector;
        highContentionAtomicDetector           = registry.highContentionAtomicDetector;
        sharedJsonMapperReconfigDetector       = registry.sharedJsonMapperReconfigDetector;
        // Phase 18 (JDK 25/26 GA)
        lazyConstantMisuseDetector             = registry.lazyConstantMisuseDetector;
        finalFieldMutationDetector             = registry.finalFieldMutationDetector;
        sharedKdfDetector                      = registry.sharedKdfDetector;
        // Executor / future / latch
        latchMisuseDetector                    = registry.latchMisuseDetector;
        executorDeadlockDetector               = registry.executorDeadlockDetector;
        futureBlockingDetector                 = registry.futureBlockingDetector;
        flowPublisherConcurrencyDetector       = registry.flowPublisherConcurrencyDetector;
        confinedArenaThreadEscapeDetector      = registry.confinedArenaThreadEscapeDetector;
        sharedMemorySegmentRaceDetector        = registry.sharedMemorySegmentRaceDetector;
        varHandleNonAtomicUpdateDetector       = registry.varHandleNonAtomicUpdateDetector;
        recordMutableComponentLeakDetector     = registry.recordMutableComponentLeakDetector;
        staticInitDeadlockDetector             = registry.staticInitDeadlockDetector;
        virtualThreadPoolingDetector           = registry.virtualThreadPoolingDetector;
        platformThreadPerTaskDetector          = registry.platformThreadPerTaskDetector;
        sharedSplittableRandomDetector         = registry.sharedSplittableRandomDetector;
        completableFutureCompletionRaceDetector          = registry.completableFutureCompletionRaceDetector;
        completableFutureCancellationPropagationDetector = registry.completableFutureCancellationPropagationDetector;
        completableFutureCombinatorMisuseDetector        = registry.completableFutureCombinatorMisuseDetector;
        lambdaLostUpdateDetector                         = registry.lambdaLostUpdateDetector;
        virtualThreadResourceSaturationDetector          = registry.virtualThreadResourceSaturationDetector;
        virtualThreadMonitorSerializationDetector        = registry.virtualThreadMonitorSerializationDetector;
        threadLocalCacheDegradationDetector              = registry.threadLocalCacheDegradationDetector;
        scopeJoinerMisuseDetector = registry.scopeJoinerMisuseDetector;
        scopeConfigurationMisuseDetector = registry.scopeConfigurationMisuseDetector;
        scopeResultEscapeDetector = registry.scopeResultEscapeDetector;
        lazyCollectionMisuseDetector = registry.lazyCollectionMisuseDetector;
        // Agent-telemetry bridge target
        atomicityValidator                     = registry.atomicityValidator;

        // Third-party SPI detectors: discovered, instantiated and started here — once per
        // @AsyncTest method, on the runner thread, before any worker thread exists — so
        // onTestStart() runs exactly where the Detector contract says it does ("before the
        // first invocation round"). Findings are merged in analyzeAllNamed().
        externalDetectors = se.deversity.asynctest.spi.DetectorRegistry.buildExternal(cfg);
        if (!externalDetectors.isEmpty()) {
            externalDetectors.fireOnTestStart();
        }
    }

    // ---- Phase 1/3 instance convergence (used by Phase1DetectorSet.from) ----
    //
    // VisibilityMonitor, LivelockDetector, RaceConditionDetector, ThreadLocalMonitor,
    // BusyWaitDetector, AtomicityValidator and InterruptMonitor were previously
    // constructed BOTH here (via DetectorRegistry) AND independently by
    // Phase1DetectorSet.from(config) in the runner. Whichever instance actually
    // received events (e.g. AtomicityValidator via the telemetry bridge below, or a
    // future instrumentation source) was silently disconnected from the other
    // instance's analysis pass, which always saw an empty detector.
    //
    // These package-crossing accessors let Phase1DetectorSet.from(config, ctx) reuse
    // this context's registry-backed instances instead of constructing duplicates, so
    // recording and analysis always observe the same object per detector. They
    // deliberately return null (rather than throwing, like the public require()-based
    // accessors) when the corresponding flag is disabled, matching
    // DetectorRegistry's null-when-disabled convention.

    /**
     * Internal: registry-backed {@link VisibilityMonitor} for this context, or
     * {@code null} when {@code detectVisibility = false}.
     *
     * <p>Same instance as the public {@link #visibilityMonitor()} accessor; unlike that
     * accessor this one returns {@code null} instead of throwing when disabled.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     *
     * @return the shared {@link VisibilityMonitor} for this context, or {@code null} when it is disabled
     */
    public @Nullable VisibilityMonitor sharedVisibilityMonitor() {
        return registry.visibilityMonitor;
    }

    /**
     * Internal: registry-backed {@link LivelockDetector} for this context, or
     * {@code null} when {@code detectLivelocks = false}.
     *
     * <p>Same instance as the public {@link #livelockDetector()} accessor; unlike that
     * accessor this one returns {@code null} instead of throwing when disabled.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     *
     * @return the shared {@link LivelockDetector} for this context, or {@code null} when it is disabled
     */
    public @Nullable LivelockDetector sharedLivelockDetector() {
        return registry.livelockDetector;
    }

    /**
     * Internal: registry-backed {@link RaceConditionDetector} for this context, or
     * {@code null} when {@code detectRaceConditions = false}.
     *
     * <p>Same instance as the public {@link #raceConditionDetector()} accessor; unlike that
     * accessor this one returns {@code null} instead of throwing when disabled.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     *
     * @return the shared {@link RaceConditionDetector} for this context, or {@code null} when it is disabled
     */
    public @Nullable RaceConditionDetector sharedRaceConditionDetector() {
        return registry.raceConditionDetector;
    }

    /**
     * Internal: registry-backed {@link ThreadLocalMonitor} for this context, or
     * {@code null} when {@code detectThreadLocalLeaks = false}.
     *
     * <p>Same instance as the public {@link #threadLocalMonitor()} accessor; unlike that
     * accessor this one returns {@code null} instead of throwing when disabled.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     *
     * @return the shared {@link ThreadLocalMonitor} for this context, or {@code null} when it is disabled
     */
    public @Nullable ThreadLocalMonitor sharedThreadLocalMonitor() {
        return registry.threadLocalMonitor;
    }

    /**
     * Internal: registry-backed {@link BusyWaitDetector} for this context, or
     * {@code null} when {@code detectBusyWaiting = false}.
     *
     * <p>Same instance as the public {@link #busyWaitDetector()} accessor; unlike that
     * accessor this one returns {@code null} instead of throwing when disabled.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     *
     * @return the shared {@link BusyWaitDetector} for this context, or {@code null} when it is disabled
     */
    public @Nullable BusyWaitDetector sharedBusyWaitDetector() {
        return registry.busyWaitDetector;
    }

    /**
     * Internal: registry-backed {@link AtomicityValidator} for this context, or
     * {@code null} when {@code detectAtomicityViolations = false}.
     *
     * <p>Same instance as the public {@link #atomicityValidator()} accessor (and thus
     * the same instance {@code TelemetryBridge} feeds); unlike that accessor this one
     * returns {@code null} instead of throwing when disabled.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     *
     * @return the shared {@link AtomicityValidator} for this context, or {@code null} when it is disabled
     */
    public @Nullable AtomicityValidator sharedAtomicityValidator() {
        return registry.atomicityValidator;
    }

    /**
     * Internal: registry-backed {@link InterruptMonitor} for this context, or
     * {@code null} when {@code detectInterruptMishandling = false}.
     *
     * <p>Same instance as the public {@link #interruptMonitor()} accessor; unlike that
     * accessor this one returns {@code null} instead of throwing when disabled.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     *
     * @return the shared {@link InterruptMonitor} for this context, or {@code null} when it is disabled
     */
    public @Nullable InterruptMonitor sharedInterruptMonitor() {
        return registry.interruptMonitor;
    }

    // ---- Lifecycle (called by ConcurrencyRunner) ----

    /**
     * Installs {@code ctx} into the calling thread's ThreadLocal.
     *
     * @param ctx the context to bind to the calling thread; must be paired with an {@code uninstall()} in a {@code finally}
     */
    @AICallersOnly({"se.deversity.asynctest.runner.ConcurrencyRunner"})
    public static void install(AsyncTestContext ctx) {
        CURRENT.set(ctx);
    }

    /**
     * Removes the context from the calling thread's ThreadLocal.
     */
    @AIIdempotent(reason = "ThreadLocal.remove() is documented as a no-op when the thread has no value set; the install/uninstall symmetry rule (CLAUDE.md) tolerates extra uninstalls. ConcurrencyRunner relies on this in its outermost-finally cleanup.")
    public static void uninstall() {
        // Both ThreadLocals go together. A declared lock that outlived its invocation would be
        // intersected into the next round's lockset and could silence a real finding there, so
        // the symmetry rule covers this one exactly as it covers CURRENT.
        HeldLocks.clear();
        CURRENT.remove();
    }

    /**
     * Declares that the calling thread holds {@code lock} until the returned guard is closed, so
     * that detectors can tell a guarded access from a racing one.
     *
     * <p>Detectors can ask {@link Thread#holdsLock(Object)} about the instance they are watching
     * and about nothing else, because that is the only lock they can name. So
     * {@code synchronized (theInstance)} is recognised for free; with the agent attached, woven
     * monitor instructions and woven {@code Lock} call sites are recognised too. What is left is
     * a lock acquired only inside code the weaver never sees, which looks exactly like no lock at
     * all, and the shared instance gets reported even though the code is correct. Declaring the
     * lock here is what tells the detectors otherwise:
     *
     * <pre>{@code
     * try (var held = AsyncTestContext.holdingLock(cacheLock)) {
     *     cacheLock.lock();
     *     try {
     *         AsyncTestContext.sharedCollectionMonitor().recordWrite(cache, "cache", "put");
     *         cache.put(k, v);
     *     } finally {
     *         cacheLock.unlock();
     *     }
     * }
     * }</pre>
     *
     * <p>What the detectors then compute is the Eraser lockset: per instance, the intersection of
     * the locks held at every recorded access. Consistent guarding leaves that set non-empty and
     * produces no finding; two threads using different locks empties it and is reported, which is
     * correct, because inconsistent locking is a race.
     *
     * <p>Safe outside a run: the declaration is per-thread bookkeeping and does not require an
     * installed context.
     *
     * @param lock the lock object being held; {@code null} yields a no-op guard
     * @return a guard to close when the lock is released, intended for try-with-resources
     * @since 1.9.6
     */
    public static HeldLocks.Guard holdingLock(@Nullable Object lock) {
        return HeldLocks.holding(lock);
    }

    /**
     * Returns the context active on the current thread, or {@code null} if called
     * outside an {@code @AsyncTest} method.
     *
     * @return the context installed on this thread, or {@code null} outside an {@code @AsyncTest} run
     */
    public static AsyncTestContext get() {
        return CURRENT.get();
    }

    // ---- Replay seed (set per-invocation by ConcurrencyRunner) ----

    /**
     * Per-round seed. Volatile because the runner thread writes it between
     * rounds while N worker threads may still be reading (they shouldn't be —
     * latch.await ensures the round is over — but volatile is the right
     * conservative discipline for cross-thread visibility).
     */
    private volatile long currentRoundSeed = 0L;

    /**
     * Returns the replay seed for the currently executing invocation round.
     *
     * <p>Use this from inside an {@code @AsyncTest} method body to seed any
     * RNG-driven choices (sleep jitter, randomised payloads, branch selection)
     * with a value the runner controls. When a test fails, the runner logs the
     * seed so you can paste it into {@code @AsyncTest(replaySeed=...)} to
     * reproduce the same RNG sequence on the next run.
     *
     * <p>Returns {@code 0L} when called outside an {@code @AsyncTest} round.
     *
     * @since 1.6.0
     *
     * @return the seed for the current invocation round
     */
    public static long replaySeed() {
        AsyncTestContext ctx = CURRENT.get();
        return ctx == null ? 0L : ctx.currentRoundSeed;
    }

    /**
     * Internal: called by {@code ConcurrencyRunner} at the start of each invocation round, after
     * the previous round's workers have all finished, so the detectors that count per round can
     * close the round in progress. Touches only this context's own detector instances, never the
     * {@code ThreadLocal}, so install/uninstall symmetry is unaffected.
     *
     * @since 1.9.8
     */
    public void markInvocationStart() {
        if (sharedCollectionDetector != null) {
            sharedCollectionDetector.markInvocationStart();
        }
        if (registry.threadLocalMonitor != null) {
            registry.threadLocalMonitor.markInvocationStart();
        }
    }

    /**
     * Internal: set by {@code ConcurrencyRunner} before each invocation round.
     *
     * @param seed the seed for this round, so a reported interleaving can be replayed
     */
    public void setReplaySeedForRound(long seed) {
        this.currentRoundSeed = seed;
    }

    // ---- Internal reporting ----

    /**
     * Every finding of this run, built-in and third-party alike, as free-text reports.
     * Called by {@link se.deversity.asynctest.runner.ConcurrencyRunner} after the test.
     *
     * <p>Derived from {@link #analyzeAllNamed()} rather than from
     * {@link DetectorRegistry#analyzeAll()} directly, so the two views can never disagree
     * about which detectors were consulted.
     *
     * @return list of non-empty issue reports; never {@code null}
     */
    public List<String> analyzeAll() {
        return new ArrayList<>(analyzeAllNamed().values());
    }

    /**
     * Delegates to {@code DetectorRegistry.analyzeAllNamed()}: the same findings
     * {@link #analyzeAll()} returns, but keyed by the simple name of the detector that
     * produced each one — then appends the findings of any third-party
     * {@link se.deversity.asynctest.spi.Detector} on the classpath.
     *
     * <p>Preferred over {@link #analyzeAll()} by anything that needs to identify a finding —
     * report attribution, listener callbacks, baseline suppression — because a detector's
     * identity must not be inferred from its report prose.
     *
     * <p>Runs on the runner thread after all workers of the round have finished; SPI
     * {@code onTestEnd()} hooks fire once, after the last analysis of the run.
     *
     * @return non-empty issue reports by detector name; never {@code null}
     * @since 1.7.0
     */
    public Map<String, String> analyzeAllNamed() {
        Map<String, String> reports = registry.analyzeAllNamed();
        appendExternalFindings(reports);
        return reports;
    }

    /**
     * {@return the per-finding grades from the most recent {@link #analyzeAllNamed()} pass}
     *
     * <p>Present only for detectors whose report implements
     * {@link se.deversity.asynctest.diagnostics.GradedFindings}. The {@code failOn} gate uses them
     * to judge a detector's findings individually rather than as one block, which is what lets a
     * verdict-grade finding fail a build even though the same detector can also produce a
     * prompt-grade one. Callers that find no entry fall back to the detector's own tier and
     * severity.
     *
     * <p>Call after {@link #analyzeAllNamed()}; on its own this returns the previous pass's
     * grades, or empty when no pass has run.
     *
     * @since 1.9.7
     */
    public Map<String, List<se.deversity.asynctest.diagnostics.GradedFindings.Grade>> findingGrades() {
        return registry.lastGrades();
    }

    /**
     * Merges third-party SPI violations into {@code reports}, keyed by
     * {@link Violation#detector()}, then fires {@code onTestEnd()} once.
     *
     * <p>Each report line opens with the violation's severity label so that
     * {@code IssueSeverity.fromReport} — the failOn gate's classifier, which only sees the
     * text — recovers the severity the detector actually assigned instead of defaulting to
     * {@code HIGH}. Several violations from one detector are joined under its single key,
     * matching how the legacy registry emits one report per detector.
     */
    private void appendExternalFindings(Map<String, String> reports) {
        if (externalDetectors.isEmpty()) {
            return;
        }
        // analyzeAll() already contains each detector's failure, so one broken third-party
        // detector cannot cost us the built-in findings collected above.
        for (Violation v : externalDetectors.analyzeAll()) {
            String line = v.severity().getLabel() + " " + v.detector() + ": " + v.message();
            reports.merge(v.detector(), line, (existing, added) -> existing + "\n" + added);
        }
        if (externalTestEndFired.compareAndSet(false, true)) {
            externalDetectors.fireOnTestEnd();
        }
    }

    // ---- Public static detector accessors ----

    /**
     * Returns the {@link FalseSharingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFalseSharing = false}
     *
     * @return the {@link FalseSharingDetector} for the active {@code @AsyncTest} context
     */
    public static FalseSharingDetector falseSharingDetector() {
        return require("detectFalseSharing", c -> c.falseSharingDetector);
    }

    /**
     * Returns the {@link WakeupDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWakeupIssues = false}
     *
     * @return the {@link WakeupDetector} for the active {@code @AsyncTest} context
     */
    public static WakeupDetector wakeupDetector() {
        return require("detectWakeupIssues", c -> c.wakeupDetector);
    }

    /**
     * Returns the {@link ConstructorSafetyValidator} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code validateConstructorSafety = false}
     *
     * @return the {@link ConstructorSafetyValidator} for the active {@code @AsyncTest} context
     */
    public static ConstructorSafetyValidator constructorSafetyValidator() {
        return require("validateConstructorSafety", c -> c.constructorSafetyValidator);
    }

    /**
     * Returns the {@link ABAProblemDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectABAProblem = false}
     *
     * @return the {@link ABAProblemDetector} for the active {@code @AsyncTest} context
     */
    public static ABAProblemDetector abaProblemDetector() {
        return require("detectABAProblem", c -> c.abaProblemDetector);
    }

    /**
     * Returns the {@link LockOrderValidator} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code validateLockOrder = false}
     *
     * @return the {@link LockOrderValidator} for the active {@code @AsyncTest} context
     */
    public static LockOrderValidator lockOrderValidator() {
        return require("validateLockOrder", c -> c.lockOrderValidator);
    }

    /**
     * Returns the {@link SynchronizerMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorSynchronizers = false}
     *
     * @return the {@link SynchronizerMonitor} for the active {@code @AsyncTest} context
     */
    public static SynchronizerMonitor synchronizerMonitor() {
        return require("monitorSynchronizers", c -> c.synchronizerMonitor);
    }

    /**
     * Returns the {@link ThreadPoolMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorThreadPool = false}
     *
     * @return the {@link ThreadPoolMonitor} for the active {@code @AsyncTest} context
     */
    public static ThreadPoolMonitor threadPoolMonitor() {
        return require("monitorThreadPool", c -> c.threadPoolMonitor);
    }

    /**
     * Returns the {@link MemoryOrderingMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMemoryOrderingViolations = false}
     *
     * @return the {@link MemoryOrderingMonitor} for the active {@code @AsyncTest} context
     */
    public static MemoryOrderingMonitor memoryOrderingMonitor() {
        return require("detectMemoryOrderingViolations", c -> c.memoryOrderingMonitor);
    }

    /**
     * Returns the {@link PipelineMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorAsyncPipeline = false}
     *
     * @return the {@link PipelineMonitor} for the active {@code @AsyncTest} context
     */
    public static PipelineMonitor pipelineMonitor() {
        return require("monitorAsyncPipeline", c -> c.pipelineMonitor);
    }

    /**
     * Returns the {@link ReadWriteLockMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorReadWriteLockFairness = false}
     *
     * @return the {@link ReadWriteLockMonitor} for the active {@code @AsyncTest} context
     */
    public static ReadWriteLockMonitor readWriteLockMonitor() {
        return require("monitorReadWriteLockFairness", c -> c.readWriteLockMonitor);
    }

    /**
     * Returns the {@link SemaphoreMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorSemaphore = false}
     * @deprecated use {@link #semaphoreMisuseDetector()}
     *
     * @return the {@link SemaphoreMisuseDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static SemaphoreMisuseDetector semaphoreMonitor() {
        return require("monitorSemaphore", c -> c.semaphoreMisuseDetector);
    }

    /**
     * Returns the {@link SemaphoreMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorSemaphore = false}
     *
     * @return the {@link SemaphoreMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static SemaphoreMisuseDetector semaphoreMisuseDetector() {
        return semaphoreMonitor();
    }

    /**
     * Returns the {@link CompletableFutureExceptionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureExceptions = false}
     * @deprecated use {@link #completableFutureExceptionDetector()}
     *
     * @return the {@link CompletableFutureExceptionDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static CompletableFutureExceptionDetector completableFutureMonitor() {
        return require("detectCompletableFutureExceptions", c -> c.completableFutureExceptionDetector);
    }

    /**
     * Returns the {@link CompletableFutureExceptionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureExceptions = false}
     *
     * @return the {@link CompletableFutureExceptionDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureExceptionDetector completableFutureExceptionDetector() {
        return completableFutureMonitor();
    }

    /**
     * Returns the {@link CompletableFutureCompletionLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureCompletionLeaks = false}
     * @since 1.2.0
     *
     * @return the {@link CompletableFutureCompletionLeakDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCompletionLeakDetector completableFutureCompletionLeakDetector() {
        return require("detectCompletableFutureCompletionLeaks", c -> c.completableFutureCompletionLeakDetector);
    }

    /**
     * Returns the {@link VirtualThreadPinningDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadPinning = false}
     * @since 1.2.0
     *
     * @return the {@link VirtualThreadPinningDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadPinningDetector virtualThreadPinningDetector() {
        return require("detectVirtualThreadPinning", c -> c.virtualThreadPinningDetector);
    }

    /**
     * Returns the {@link ThreadPoolDeadlockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadPoolDeadlocks = false}
     * @since 1.2.0
     *
     * @return the {@link ThreadPoolDeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadPoolDeadlockDetector threadPoolDeadlockDetector() {
        return require("detectThreadPoolDeadlocks", c -> c.threadPoolDeadlockDetector);
    }

    /**
     * Returns the {@link ConcurrentModificationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentModifications = false}
     * @deprecated use {@link #concurrentModificationDetector()}
     *
     * @return the {@link ConcurrentModificationDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ConcurrentModificationDetector concurrentModificationMonitor() {
        return require("detectConcurrentModifications", c -> c.concurrentModificationDetector);
    }

    /**
     * Returns the {@link ConcurrentModificationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentModifications = false}
     *
     * @return the {@link ConcurrentModificationDetector} for the active {@code @AsyncTest} context
     */
    public static ConcurrentModificationDetector concurrentModificationDetector() {
        return concurrentModificationMonitor();
    }

    /**
     * Returns the {@link LockLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockLeaks = false}
     * @deprecated use {@link #lockLeakDetector()}
     *
     * @return the {@link LockLeakDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static LockLeakDetector lockLeakMonitor() {
        return require("detectLockLeaks", c -> c.lockLeakDetector);
    }

    /**
     * Returns the {@link LockLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockLeaks = false}
     *
     * @return the {@link LockLeakDetector} for the active {@code @AsyncTest} context
     */
    public static LockLeakDetector lockLeakDetector() {
        return lockLeakMonitor();
    }

    /**
     * Returns the {@link SharedRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedRandom = false}
     * @deprecated use {@link #sharedRandomDetector()}
     *
     * @return the {@link SharedRandomDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static SharedRandomDetector sharedRandomMonitor() {
        return require("detectSharedRandom", c -> c.sharedRandomDetector);
    }

    /**
     * Returns the {@link SharedRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedRandom = false}
     *
     * @return the {@link SharedRandomDetector} for the active {@code @AsyncTest} context
     */
    public static SharedRandomDetector sharedRandomDetector() {
        return sharedRandomMonitor();
    }

    /**
     * Returns the {@link BlockingQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBlockingQueueIssues = false}
     * @deprecated use {@link #blockingQueueDetector()}
     *
     * @return the {@link BlockingQueueDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static BlockingQueueDetector blockingQueueMonitor() {
        return require("detectBlockingQueueIssues", c -> c.blockingQueueDetector);
    }

    /**
     * Returns the {@link BlockingQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBlockingQueueIssues = false}
     *
     * @return the {@link BlockingQueueDetector} for the active {@code @AsyncTest} context
     */
    public static BlockingQueueDetector blockingQueueDetector() {
        return blockingQueueMonitor();
    }

    /**
     * Returns the {@link ConditionVariableDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConditionVariableIssues = false}
     * @deprecated use {@link #conditionVariableDetector()}
     *
     * @return the {@link ConditionVariableDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ConditionVariableDetector conditionMonitor() {
        return require("detectConditionVariableIssues", c -> c.conditionVariableDetector);
    }

    /**
     * Returns the {@link ConditionVariableDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConditionVariableIssues = false}
     *
     * @return the {@link ConditionVariableDetector} for the active {@code @AsyncTest} context
     */
    public static ConditionVariableDetector conditionVariableDetector() {
        return conditionMonitor();
    }

    /**
     * Returns the {@link SimpleDateFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSimpleDateFormatIssues = false}
     * @deprecated use {@link #simpleDateFormatDetector()}
     *
     * @return the {@link SimpleDateFormatDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static SimpleDateFormatDetector simpleDateFormatMonitor() {
        return require("detectSimpleDateFormatIssues", c -> c.simpleDateFormatDetector);
    }

    /**
     * Returns the {@link SimpleDateFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSimpleDateFormatIssues = false}
     *
     * @return the {@link SimpleDateFormatDetector} for the active {@code @AsyncTest} context
     */
    public static SimpleDateFormatDetector simpleDateFormatDetector() {
        return simpleDateFormatMonitor();
    }

    /**
     * Returns the {@link ParallelStreamDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectParallelStreamIssues = false}
     * @deprecated use {@link #parallelStreamDetector()}
     *
     * @return the {@link ParallelStreamDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ParallelStreamDetector parallelStreamMonitor() {
        return require("detectParallelStreamIssues", c -> c.parallelStreamDetector);
    }

    /**
     * Returns the {@link ParallelStreamDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectParallelStreamIssues = false}
     *
     * @return the {@link ParallelStreamDetector} for the active {@code @AsyncTest} context
     */
    public static ParallelStreamDetector parallelStreamDetector() {
        return parallelStreamMonitor();
    }

    /**
     * Returns the {@link ResourceLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectResourceLeaks = false}
     * @deprecated use {@link #resourceLeakDetector()}
     *
     * @return the {@link ResourceLeakDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ResourceLeakDetector resourceLeakMonitor() {
        return require("detectResourceLeaks", c -> c.resourceLeakDetector);
    }

    /**
     * Returns the {@link ResourceLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectResourceLeaks = false}
     *
     * @return the {@link ResourceLeakDetector} for the active {@code @AsyncTest} context
     */
    public static ResourceLeakDetector resourceLeakDetector() {
        return resourceLeakMonitor();
    }

    /**
     * Returns the {@link CountDownLatchDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCountDownLatchIssues = false}
     * @deprecated use {@link #countDownLatchDetector()}
     *
     * @return the {@link CountDownLatchDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static CountDownLatchDetector countDownLatchMonitor() {
        return require("detectCountDownLatchIssues", c -> c.countDownLatchDetector);
    }

    /**
     * Returns the {@link CountDownLatchDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCountDownLatchIssues = false}
     *
     * @return the {@link CountDownLatchDetector} for the active {@code @AsyncTest} context
     */
    public static CountDownLatchDetector countDownLatchDetector() {
        return countDownLatchMonitor();
    }

    /**
     * Returns the {@link CyclicBarrierDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCyclicBarrierIssues = false}
     * @deprecated use {@link #cyclicBarrierDetector()}
     *
     * @return the {@link CyclicBarrierDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static CyclicBarrierDetector cyclicBarrierMonitor() {
        return require("detectCyclicBarrierIssues", c -> c.cyclicBarrierDetector);
    }

    /**
     * Returns the {@link CyclicBarrierDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCyclicBarrierIssues = false}
     *
     * @return the {@link CyclicBarrierDetector} for the active {@code @AsyncTest} context
     */
    public static CyclicBarrierDetector cyclicBarrierDetector() {
        return cyclicBarrierMonitor();
    }

    /**
     * Returns the {@link ReentrantLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectReentrantLockIssues = false}
     * @deprecated use {@link #reentrantLockDetector()}
     *
     * @return the {@link ReentrantLockDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ReentrantLockDetector reentrantLockMonitor() {
        return require("detectReentrantLockIssues", c -> c.reentrantLockDetector);
    }

    /**
     * Returns the {@link ReentrantLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectReentrantLockIssues = false}
     *
     * @return the {@link ReentrantLockDetector} for the active {@code @AsyncTest} context
     */
    public static ReentrantLockDetector reentrantLockDetector() {
        return reentrantLockMonitor();
    }

    /**
     * Returns the {@link VolatileArrayDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVolatileArrayIssues = false}
     * @deprecated use {@link #volatileArrayDetector()}
     *
     * @return the {@link VolatileArrayDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static VolatileArrayDetector volatileArrayMonitor() {
        return require("detectVolatileArrayIssues", c -> c.volatileArrayDetector);
    }

    /**
     * Returns the {@link VolatileArrayDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVolatileArrayIssues = false}
     *
     * @return the {@link VolatileArrayDetector} for the active {@code @AsyncTest} context
     */
    public static VolatileArrayDetector volatileArrayDetector() {
        return volatileArrayMonitor();
    }

    /**
     * Returns the {@link DoubleCheckedLockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDoubleCheckedLocking = false}
     * @deprecated use {@link #doubleCheckedLockingDetector()}
     *
     * @return the {@link DoubleCheckedLockingDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static DoubleCheckedLockingDetector doubleCheckedLockingMonitor() {
        return require("detectDoubleCheckedLocking", c -> c.doubleCheckedLockingDetector);
    }

    /**
     * Returns the {@link DoubleCheckedLockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDoubleCheckedLocking = false}
     *
     * @return the {@link DoubleCheckedLockingDetector} for the active {@code @AsyncTest} context
     */
    public static DoubleCheckedLockingDetector doubleCheckedLockingDetector() {
        return doubleCheckedLockingMonitor();
    }

    /**
     * Returns the {@link WaitTimeoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWaitTimeout = false}
     * @deprecated use {@link #waitTimeoutDetector()}
     *
     * @return the {@link WaitTimeoutDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static WaitTimeoutDetector waitTimeoutMonitor() {
        return require("detectWaitTimeout", c -> c.waitTimeoutDetector);
    }

    /**
     * Returns the {@link WaitTimeoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWaitTimeout = false}
     *
     * @return the {@link WaitTimeoutDetector} for the active {@code @AsyncTest} context
     */
    public static WaitTimeoutDetector waitTimeoutDetector() {
        return waitTimeoutMonitor();
    }

    /**
     * Returns the {@link LockContentionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockContention = false}
     *
     * @return the {@link LockContentionDetector} for the active {@code @AsyncTest} context
     */
    public static LockContentionDetector lockContentionDetector() {
        return require("detectLockContention", c -> c.lockContentionDetector);
    }

    /**
     * Returns the {@link SynchronizedNonFinalDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedNonFinal = false}
     *
     * @return the {@link SynchronizedNonFinalDetector} for the active {@code @AsyncTest} context
     */
    public static SynchronizedNonFinalDetector synchronizedNonFinalDetector() {
        return require("detectSynchronizedNonFinal", c -> c.synchronizedNonFinalDetector);
    }

    /**
     * Returns the {@link MissedSignalDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMissedSignals = false}
     *
     * @return the {@link MissedSignalDetector} for the active {@code @AsyncTest} context
     */
    public static MissedSignalDetector missedSignalDetector() {
        return require("detectMissedSignals", c -> c.missedSignalDetector);
    }

    /**
     * Returns the {@link LazyInitRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLazyInitRace = false}
     *
     * @return the {@link LazyInitRaceDetector} for the active {@code @AsyncTest} context
     */
    public static LazyInitRaceDetector lazyInitRaceDetector() {
        return require("detectLazyInitRace", c -> c.lazyInitRaceDetector);
    }

    /**
     * Returns the {@link PhaserDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPhaserIssues = false}
     * @deprecated use {@link #phaserDetector()}
     *
     * @return the {@link PhaserDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static PhaserDetector phaserMonitor() {
        return require("detectPhaserIssues", c -> c.phaserDetector);
    }

    /**
     * Returns the {@link PhaserDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPhaserIssues = false}
     *
     * @return the {@link PhaserDetector} for the active {@code @AsyncTest} context
     */
    public static PhaserDetector phaserDetector() {
        return phaserMonitor();
    }

    /**
     * Returns the {@link StampedLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStampedLockIssues = false}
     * @deprecated use {@link #stampedLockDetector()}
     *
     * @return the {@link StampedLockDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static StampedLockDetector stampedLockMonitor() {
        return require("detectStampedLockIssues", c -> c.stampedLockDetector);
    }

    /**
     * Returns the {@link StampedLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStampedLockIssues = false}
     *
     * @return the {@link StampedLockDetector} for the active {@code @AsyncTest} context
     */
    public static StampedLockDetector stampedLockDetector() {
        return stampedLockMonitor();
    }

    /**
     * Returns the {@link ExchangerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExchangerIssues = false}
     * @deprecated use {@link #exchangerDetector()}
     *
     * @return the {@link ExchangerDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ExchangerDetector exchangerMonitor() {
        return require("detectExchangerIssues", c -> c.exchangerDetector);
    }

    /**
     * Returns the {@link ExchangerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExchangerIssues = false}
     *
     * @return the {@link ExchangerDetector} for the active {@code @AsyncTest} context
     */
    public static ExchangerDetector exchangerDetector() {
        return exchangerMonitor();
    }

    /**
     * Returns the {@link ScheduledExecutorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScheduledExecutorIssues = false}
     * @deprecated use {@link #scheduledExecutorDetector()}
     *
     * @return the {@link ScheduledExecutorDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ScheduledExecutorDetector scheduledExecutorMonitor() {
        return require("detectScheduledExecutorIssues", c -> c.scheduledExecutorDetector);
    }

    /**
     * Returns the {@link ScheduledExecutorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScheduledExecutorIssues = false}
     *
     * @return the {@link ScheduledExecutorDetector} for the active {@code @AsyncTest} context
     */
    public static ScheduledExecutorDetector scheduledExecutorDetector() {
        return scheduledExecutorMonitor();
    }

    /**
     * Returns the {@link ForkJoinPoolDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinPoolIssues = false}
     * @deprecated use {@link #forkJoinPoolDetector()}
     *
     * @return the {@link ForkJoinPoolDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ForkJoinPoolDetector forkJoinPoolMonitor() {
        return require("detectForkJoinPoolIssues", c -> c.forkJoinPoolDetector);
    }

    /**
     * Returns the {@link ForkJoinPoolDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinPoolIssues = false}
     *
     * @return the {@link ForkJoinPoolDetector} for the active {@code @AsyncTest} context
     */
    public static ForkJoinPoolDetector forkJoinPoolDetector() {
        return forkJoinPoolMonitor();
    }

    /**
     * Returns the {@link ThreadFactoryDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadFactoryIssues = false}
     * @deprecated use {@link #threadFactoryDetector()}
     *
     * @return the {@link ThreadFactoryDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ThreadFactoryDetector threadFactoryMonitor() {
        return require("detectThreadFactoryIssues", c -> c.threadFactoryDetector);
    }

    /**
     * Returns the {@link ThreadFactoryDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadFactoryIssues = false}
     *
     * @return the {@link ThreadFactoryDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadFactoryDetector threadFactoryDetector() {
        return threadFactoryMonitor();
    }

    // ---- Phase 4: Infrastructure & Resource Management ----

    /**
     * Returns the {@link ThreadLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLeaks = false}
     *
     * @return the {@link ThreadLeakDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadLeakDetector threadLeakDetector() {
        return require("detectThreadLeaks", c -> c.threadLeakDetector);
    }

    /**
     * Returns the {@link SleepInLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSleepInLock = false}
     *
     * @return the {@link SleepInLockDetector} for the active {@code @AsyncTest} context
     */
    public static SleepInLockDetector sleepInLockDetector() {
        return require("detectSleepInLock", c -> c.sleepInLockDetector);
    }

    /**
     * Returns the {@link UnboundedQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectUnboundedQueue = false}
     *
     * @return the {@link UnboundedQueueDetector} for the active {@code @AsyncTest} context
     */
    public static UnboundedQueueDetector unboundedQueueDetector() {
        return require("detectUnboundedQueue", c -> c.unboundedQueueDetector);
    }

    /**
     * Returns the {@link ThreadStarvationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadStarvation = false}
     *
     * @return the {@link ThreadStarvationDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadStarvationDetector threadStarvationDetector() {
        return require("detectThreadStarvation", c -> c.threadStarvationDetector);
    }

    // ---- Phase 5: Thread-Safety of Common Types ----

    /**
     * Returns the {@link CalendarDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCalendarIssues = false}
     * @deprecated use {@link #calendarDetector()}
     *
     * @return the {@link CalendarDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static CalendarDetector calendarMonitor() {
        return require("detectCalendarIssues", c -> c.calendarDetector);
    }

    /**
     * Returns the {@link CalendarDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCalendarIssues = false}
     *
     * @return the {@link CalendarDetector} for the active {@code @AsyncTest} context
     */
    public static CalendarDetector calendarDetector() {
        return calendarMonitor();
    }

    /**
     * Returns the {@link SharedCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedCollections = false}
     * @deprecated use {@link #sharedCollectionDetector()}
     *
     * @return the {@link SharedCollectionDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static SharedCollectionDetector sharedCollectionMonitor() {
        return require("detectSharedCollections", c -> c.sharedCollectionDetector);
    }

    /**
     * Returns the {@link SharedCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedCollections = false}
     *
     * @return the {@link SharedCollectionDetector} for the active {@code @AsyncTest} context
     */
    public static SharedCollectionDetector sharedCollectionDetector() {
        return sharedCollectionMonitor();
    }

    /**
     * {@return the current thread's {@link SharedCollectionDetector}, or {@code null} when there is
     * none}
     *
     * <p>The public accessors throw when called outside an {@code @AsyncTest} or with the detector
     * switched off, which is right for a test author who asked for something that is not there.
     * {@link AgentCollectionHooks} asks a different question: it runs inside woven third-party code
     * that has no idea a test is in progress, so "no context here" is the ordinary case and must
     * cost a null check rather than an exception. Reads the same {@code ThreadLocal} and installs
     * nothing, so it cannot affect the install/uninstall symmetry the class contract requires.
     */
    static @Nullable SharedCollectionDetector currentSharedCollectionDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.sharedCollectionDetector;
    }

    /**
     * {@return the {@link LockOrderValidator} for the calling thread's test, or {@code null}}
     *
     * <p>Same contract as {@link #currentSharedCollectionDetector()}, for the same reason:
     * {@link AgentLockHooks} runs inside woven code that does not know a test is in progress.
     */
    static @Nullable LockOrderValidator currentLockOrderValidator() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.lockOrderValidator;
    }

    /** {@return the {@link LockLeakDetector} for the calling thread's test, or {@code null}} */
    static @Nullable LockLeakDetector currentLockLeakDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.lockLeakDetector;
    }

    /** {@return the {@link TryLockMisuseDetector} for the calling thread's test, or {@code null}} */
    static @Nullable TryLockMisuseDetector currentTryLockMisuseDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.tryLockMisuseDetector;
    }

    /**
     * Returns the {@link TimerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectTimerIssues = false}
     * @deprecated use {@link #timerDetector()}
     *
     * @return the {@link TimerDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static TimerDetector timerMonitor() {
        return require("detectTimerIssues", c -> c.timerDetector);
    }

    /**
     * Returns the {@link TimerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectTimerIssues = false}
     *
     * @return the {@link TimerDetector} for the active {@code @AsyncTest} context
     */
    public static TimerDetector timerDetector() {
        return timerMonitor();
    }

    /**
     * Returns the {@link CopyOnWriteCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCopyOnWriteCollectionIssues = false}
     * @deprecated use {@link #copyOnWriteCollectionDetector()}
     *
     * @return the {@link CopyOnWriteCollectionDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static CopyOnWriteCollectionDetector copyOnWriteMonitor() {
        return require("detectCopyOnWriteCollectionIssues", c -> c.copyOnWriteCollectionDetector);
    }

    /**
     * Returns the {@link CopyOnWriteCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCopyOnWriteCollectionIssues = false}
     *
     * @return the {@link CopyOnWriteCollectionDetector} for the active {@code @AsyncTest} context
     */
    public static CopyOnWriteCollectionDetector copyOnWriteCollectionDetector() {
        return copyOnWriteMonitor();
    }

    /**
     * Returns the {@link StringBuilderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStringBuilderIssues = false}
     * @deprecated use {@link #stringBuilderDetector()}
     *
     * @return the {@link StringBuilderDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static StringBuilderDetector stringBuilderMonitor() {
        return require("detectStringBuilderIssues", c -> c.stringBuilderDetector);
    }

    /**
     * Returns the {@link StringBuilderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStringBuilderIssues = false}
     *
     * @return the {@link StringBuilderDetector} for the active {@code @AsyncTest} context
     */
    public static StringBuilderDetector stringBuilderDetector() {
        return stringBuilderMonitor();
    }

    // ---- Phase 6: Virtual Thread Concurrency (Java 21+) ----

    /**
     * Returns the {@link StructuredConcurrencyMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStructuredConcurrencyIssues = false}
     *
     * @return the {@link StructuredConcurrencyMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static StructuredConcurrencyMisuseDetector structuredConcurrencyMisuseDetector() {
        return require("detectStructuredConcurrencyIssues", c -> c.structuredConcurrencyMisuseDetector);
    }

    /**
     * Returns the {@link VirtualThreadContextLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadContextLeaks = false}
     *
     * @return the {@link VirtualThreadContextLeakDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadContextLeakDetector virtualThreadContextLeakDetector() {
        return require("detectVirtualThreadContextLeaks", c -> c.virtualThreadContextLeakDetector);
    }

    /**
     * Returns the {@link ScopedValueMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScopedValueMisuse = false}
     *
     * @return the {@link ScopedValueMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static ScopedValueMisuseDetector scopedValueMisuseDetector() {
        return require("detectScopedValueMisuse", c -> c.scopedValueMisuseDetector);
    }

    /**
     * Returns the {@link VirtualThreadCpuBoundTaskDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadCpuBoundTasks = false}
     * @since 0.7.0
     *
     * @return the {@link VirtualThreadCpuBoundTaskDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadCpuBoundTaskDetector virtualThreadCpuBoundTaskDetector() {
        return require("detectVirtualThreadCpuBoundTasks", c -> c.virtualThreadCpuBoundTaskDetector);
    }

    /**
     * Returns the {@link VirtualThreadCarrierExhaustionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadCarrierExhaustion = false}
     * @since 0.7.0
     *
     * @return the {@link VirtualThreadCarrierExhaustionDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadCarrierExhaustionDetector virtualThreadCarrierExhaustionDetector() {
        return require("detectVirtualThreadCarrierExhaustion", c -> c.virtualThreadCarrierExhaustionDetector);
    }

    // ---- Phase 7: High-Level Concurrency Patterns ----

    /**
     * Returns the {@link HttpClientConcurrencyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectHttpClientIssues = false}
     * @since 0.7.0
     *
     * @return the {@link HttpClientConcurrencyDetector} for the active {@code @AsyncTest} context
     */
    public static HttpClientConcurrencyDetector httpClientDetector() {
        return require("detectHttpClientIssues", c -> c.httpClientConcurrencyDetector);
    }

    /**
     * Returns the {@link StreamClosingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStreamClosing = false}
     * @since 0.7.0
     *
     * @return the {@link StreamClosingDetector} for the active {@code @AsyncTest} context
     */
    public static StreamClosingDetector streamClosingDetector() {
        return require("detectStreamClosing", c -> c.streamClosingDetector);
    }

    /**
     * Returns the {@link CacheConcurrencyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCacheConcurrency = false}
     * @since 0.7.0
     *
     * @return the {@link CacheConcurrencyDetector} for the active {@code @AsyncTest} context
     */
    public static CacheConcurrencyDetector cacheConcurrencyDetector() {
        return require("detectCacheConcurrency", c -> c.cacheConcurrencyDetector);
    }

    /**
     * Returns the {@link CompletableFutureChainDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureChainIssues = false}
     * @since 0.7.0
     *
     * @return the {@link CompletableFutureChainDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureChainDetector cfChainDetector() {
        return require("detectCompletableFutureChainIssues", c -> c.completableFutureChainDetector);
    }

    // ---- Phase 8: Lifecycle & Structural Correctness ----

    /**
     * Returns the {@link ExecutorShutdownDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExecutorShutdown = false}
     * @deprecated use {@link #executorShutdownDetector()}
     *
     * @return the {@link ExecutorShutdownDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ExecutorShutdownDetector executorShutdownMonitor() {
        return require("detectExecutorShutdown", c -> c.executorShutdownDetector);
    }

    /**
     * Returns the {@link ExecutorShutdownDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExecutorShutdown = false}
     *
     * @return the {@link ExecutorShutdownDetector} for the active {@code @AsyncTest} context
     */
    public static ExecutorShutdownDetector executorShutdownDetector() {
        return executorShutdownMonitor();
    }

    /**
     * Returns the {@link MutableMapKeyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMutableMapKeys = false}
     * @deprecated use {@link #mutableMapKeyDetector()}
     *
     * @return the {@link MutableMapKeyDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static MutableMapKeyDetector mutableMapKeyMonitor() {
        return require("detectMutableMapKeys", c -> c.mutableMapKeyDetector);
    }

    /**
     * Returns the {@link MutableMapKeyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMutableMapKeys = false}
     *
     * @return the {@link MutableMapKeyDetector} for the active {@code @AsyncTest} context
     */
    public static MutableMapKeyDetector mutableMapKeyDetector() {
        return mutableMapKeyMonitor();
    }

    /**
     * Returns the {@link NestedMonitorLockoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectNestedMonitorLockout = false}
     * @deprecated use {@link #nestedMonitorLockoutDetector()}
     *
     * @return the {@link NestedMonitorLockoutDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static NestedMonitorLockoutDetector nestedMonitorLockoutMonitor() {
        return require("detectNestedMonitorLockout", c -> c.nestedMonitorLockoutDetector);
    }

    /**
     * Returns the {@link NestedMonitorLockoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectNestedMonitorLockout = false}
     *
     * @return the {@link NestedMonitorLockoutDetector} for the active {@code @AsyncTest} context
     */
    public static NestedMonitorLockoutDetector nestedMonitorLockoutDetector() {
        return nestedMonitorLockoutMonitor();
    }

    /**
     * Returns the {@link LockDowngradeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockDowngrade = false}
     * @deprecated use {@link #lockDowngradeDetector()}
     *
     * @return the {@link LockDowngradeDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static LockDowngradeDetector lockDowngradeMonitor() {
        return require("detectLockDowngrade", c -> c.lockDowngradeDetector);
    }

    /**
     * Returns the {@link LockDowngradeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockDowngrade = false}
     *
     * @return the {@link LockDowngradeDetector} for the active {@code @AsyncTest} context
     */
    public static LockDowngradeDetector lockDowngradeDetector() {
        return lockDowngradeMonitor();
    }

    /**
     * Returns the {@link InheritableThreadLocalMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectInheritableThreadLocalMisuse = false}
     * @deprecated use {@link #inheritableThreadLocalMisuseDetector()}
     *
     * @return the {@link InheritableThreadLocalMisuseDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseMonitor() {
        return require("detectInheritableThreadLocalMisuse", c -> c.inheritableThreadLocalMisuseDetector);
    }

    /**
     * Returns the {@link InheritableThreadLocalMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectInheritableThreadLocalMisuse = false}
     *
     * @return the {@link InheritableThreadLocalMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseDetector() {
        return inheritableThreadLocalMisuseMonitor();
    }

    /**
     * Returns the {@link ThreadLocalContaminationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalContamination = false}
     * @deprecated use {@link #threadLocalContaminationDetector()}
     *
     * @return the {@link ThreadLocalContaminationDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ThreadLocalContaminationDetector threadLocalContaminationMonitor() {
        return require("detectThreadLocalContamination", c -> c.threadLocalContaminationDetector);
    }

    /**
     * Returns the {@link ThreadLocalContaminationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalContamination = false}
     *
     * @return the {@link ThreadLocalContaminationDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadLocalContaminationDetector threadLocalContaminationDetector() {
        return threadLocalContaminationMonitor();
    }

    /**
     * Returns the {@link AtomicNonAtomicUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectAtomicNonAtomicUpdates = false}
     * @deprecated use {@link #atomicNonAtomicUpdateDetector()}
     *
     * @return the {@link AtomicNonAtomicUpdateDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static AtomicNonAtomicUpdateDetector atomicNonAtomicUpdateMonitor() {
        return require("detectAtomicNonAtomicUpdates", c -> c.atomicNonAtomicUpdateDetector);
    }

    /**
     * Returns the {@link AtomicNonAtomicUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectAtomicNonAtomicUpdates = false}
     *
     * @return the {@link AtomicNonAtomicUpdateDetector} for the active {@code @AsyncTest} context
     */
    public static AtomicNonAtomicUpdateDetector atomicNonAtomicUpdateDetector() {
        return atomicNonAtomicUpdateMonitor();
    }

    /**
     * Returns the {@link SynchronizedCollectionIterationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedCollectionIteration = false}
     * @deprecated use {@link #synchronizedCollectionIterationDetector()}
     *
     * @return the {@link SynchronizedCollectionIterationDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static SynchronizedCollectionIterationDetector synchronizedCollectionIterationMonitor() {
        return require("detectSynchronizedCollectionIteration", c -> c.synchronizedCollectionIterationDetector);
    }

    /**
     * Returns the {@link SynchronizedCollectionIterationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedCollectionIteration = false}
     *
     * @return the {@link SynchronizedCollectionIterationDetector} for the active {@code @AsyncTest} context
     */
    public static SynchronizedCollectionIterationDetector synchronizedCollectionIterationDetector() {
        return synchronizedCollectionIterationMonitor();
    }

    /**
     * Returns the {@link SharedFormatterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedFormatter = false}
     * @deprecated use {@link #sharedFormatterDetector()}
     *
     * @return the {@link SharedFormatterDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static SharedFormatterDetector sharedFormatterMonitor() {
        return require("detectSharedFormatter", c -> c.sharedFormatterDetector);
    }

    /**
     * Returns the {@link SharedFormatterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedFormatter = false}
     *
     * @return the {@link SharedFormatterDetector} for the active {@code @AsyncTest} context
     */
    public static SharedFormatterDetector sharedFormatterDetector() {
        return sharedFormatterMonitor();
    }

    /**
     * Returns the {@link ConcurrentMapComputeRecursionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentMapComputeRecursion = false}
     * @deprecated use {@link #concurrentMapComputeRecursionDetector()}
     *
     * @return the {@link ConcurrentMapComputeRecursionDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ConcurrentMapComputeRecursionDetector concurrentMapComputeRecursionMonitor() {
        return require("detectConcurrentMapComputeRecursion", c -> c.concurrentMapComputeRecursionDetector);
    }

    /**
     * Returns the {@link ConcurrentMapComputeRecursionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentMapComputeRecursion = false}
     *
     * @return the {@link ConcurrentMapComputeRecursionDetector} for the active {@code @AsyncTest} context
     */
    public static ConcurrentMapComputeRecursionDetector concurrentMapComputeRecursionDetector() {
        return concurrentMapComputeRecursionMonitor();
    }

    /**
     * Returns the {@link SynchronizedOnLiteralDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedOnLiteral = false}
     * @deprecated use {@link #synchronizedOnLiteralDetector()}
     *
     * @return the {@link SynchronizedOnLiteralDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static SynchronizedOnLiteralDetector synchronizedOnLiteralMonitor() {
        return require("detectSynchronizedOnLiteral", c -> c.synchronizedOnLiteralDetector);
    }

    /**
     * Returns the {@link SynchronizedOnLiteralDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedOnLiteral = false}
     *
     * @return the {@link SynchronizedOnLiteralDetector} for the active {@code @AsyncTest} context
     */
    public static SynchronizedOnLiteralDetector synchronizedOnLiteralDetector() {
        return synchronizedOnLiteralMonitor();
    }

    /**
     * Returns the {@link PublicLockExposureDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPublicLockExposure = false}
     * @deprecated use {@link #publicLockExposureDetector()}
     *
     * @return the {@link PublicLockExposureDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static PublicLockExposureDetector publicLockExposureMonitor() {
        return require("detectPublicLockExposure", c -> c.publicLockExposureDetector);
    }

    /**
     * Returns the {@link PublicLockExposureDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPublicLockExposure = false}
     *
     * @return the {@link PublicLockExposureDetector} for the active {@code @AsyncTest} context
     */
    public static PublicLockExposureDetector publicLockExposureDetector() {
        return publicLockExposureMonitor();
    }

    /**
     * Returns the {@link ForkJoinTaskBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinTaskBlocking = false}
     * @deprecated use {@link #forkJoinTaskBlockingDetector()}
     *
     * @return the {@link ForkJoinTaskBlockingDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static ForkJoinTaskBlockingDetector forkJoinTaskBlockingMonitor() {
        return require("detectForkJoinTaskBlocking", c -> c.forkJoinTaskBlockingDetector);
    }

    /**
     * Returns the {@link ForkJoinTaskBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinTaskBlocking = false}
     *
     * @return the {@link ForkJoinTaskBlockingDetector} for the active {@code @AsyncTest} context
     */
    public static ForkJoinTaskBlockingDetector forkJoinTaskBlockingDetector() {
        return forkJoinTaskBlockingMonitor();
    }

    /**
     * Returns the {@link OptimisticReadValidationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectOptimisticReadValidation = false}
     * @deprecated use {@link #optimisticReadValidationDetector()}
     *
     * @return the {@link OptimisticReadValidationDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static OptimisticReadValidationDetector optimisticReadValidationMonitor() {
        return require("detectOptimisticReadValidation", c -> c.optimisticReadValidationDetector);
    }

    /**
     * Returns the {@link OptimisticReadValidationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectOptimisticReadValidation = false}
     *
     * @return the {@link OptimisticReadValidationDetector} for the active {@code @AsyncTest} context
     */
    public static OptimisticReadValidationDetector optimisticReadValidationDetector() {
        return optimisticReadValidationMonitor();
    }

    /**
     * Returns the {@link CompletableFutureCommonPoolBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCFCommonPoolBlocking = false}
     * @deprecated use {@link #cfCommonPoolBlockingDetector()}
     *
     * @return the {@link CompletableFutureCommonPoolBlockingDetector} for the active {@code @AsyncTest} context
     */
    @Deprecated
    public static CompletableFutureCommonPoolBlockingDetector cfCommonPoolBlockingMonitor() {
        return require("detectCFCommonPoolBlocking", c -> c.cfCommonPoolBlockingDetector);
    }

    /**
     * Returns the {@link CompletableFutureCommonPoolBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCFCommonPoolBlocking = false}
     *
     * @return the {@link CompletableFutureCommonPoolBlockingDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCommonPoolBlockingDetector cfCommonPoolBlockingDetector() {
        return cfCommonPoolBlockingMonitor();
    }

    // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----

    /**
     * Returns the {@link SharedMatcherDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedMatcher = false}
     * @since 0.9.0
     *
     * @return the {@link SharedMatcherDetector} for the active {@code @AsyncTest} context
     */
    public static SharedMatcherDetector sharedMatcherDetector() {
        return require("detectSharedMatcher", c -> c.sharedMatcherDetector);
    }

    /**
     * Returns the {@link SharedDecimalFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedDecimalFormat = false}
     * @since 0.9.0
     *
     * @return the {@link SharedDecimalFormatDetector} for the active {@code @AsyncTest} context
     */
    public static SharedDecimalFormatDetector sharedDecimalFormatDetector() {
        return require("detectSharedDecimalFormat", c -> c.sharedDecimalFormatDetector);
    }

    /**
     * Returns the {@link WeakReferenceRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWeakReferenceRace = false}
     * @since 0.9.0
     *
     * @return the {@link WeakReferenceRaceDetector} for the active {@code @AsyncTest} context
     */
    public static WeakReferenceRaceDetector weakReferenceRaceDetector() {
        return require("detectWeakReferenceRace", c -> c.weakReferenceRaceDetector);
    }

    /**
     * Returns the {@link StatefulLambdaDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStatefulLambda = false}
     * @since 0.9.0
     *
     * @return the {@link StatefulLambdaDetector} for the active {@code @AsyncTest} context
     */
    public static StatefulLambdaDetector statefulLambdaDetector() {
        return require("detectStatefulLambda", c -> c.statefulLambdaDetector);
    }

    /**
     * Returns the {@link SharedMessageDigestDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedMessageDigest = false}
     * @since 0.9.0
     *
     * @return the {@link SharedMessageDigestDetector} for the active {@code @AsyncTest} context
     */
    @AIPublicAPI
    public static SharedMessageDigestDetector sharedMessageDigestDetector() {
        return require("detectSharedMessageDigest", c -> c.sharedMessageDigestDetector);
    }

    /**
     * Returns the {@link SharedMessageDigestDetector} (as a unified Shared Cryptography Detector) for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedMessageDigest = false}
     * @since 0.9.5
     *
     * @return the {@link SharedMessageDigestDetector} for the active {@code @AsyncTest} context
     */
    @AIPublicAPI
    public static SharedMessageDigestDetector sharedCryptographyDetector() {
        return sharedMessageDigestDetector();
    }

    // ---- Phase 12: Operational & Hygiene Concurrency Issues ----

    /**
     * Returns the {@link InterruptSwallowingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectInterruptSwallowing = false}
     * @since 0.10.0
     *
     * @return the {@link InterruptSwallowingDetector} for the active {@code @AsyncTest} context
     */
    public static InterruptSwallowingDetector interruptSwallowingDetector() {
        return require("detectInterruptSwallowing", c -> c.interruptSwallowingDetector);
    }

    /**
     * Returns the {@link MdcContextLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMdcContextLeak = false}
     * @since 0.10.0
     *
     * @return the {@link MdcContextLeakDetector} for the active {@code @AsyncTest} context
     */
    public static MdcContextLeakDetector mdcContextLeakDetector() {
        return require("detectMdcContextLeak", c -> c.mdcContextLeakDetector);
    }

    /**
     * Returns the {@link SystemPropertyMutationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSystemPropertyMutation = false}
     * @since 0.10.0
     *
     * @return the {@link SystemPropertyMutationDetector} for the active {@code @AsyncTest} context
     */
    public static SystemPropertyMutationDetector systemPropertyMutationDetector() {
        return require("detectSystemPropertyMutation", c -> c.systemPropertyMutationDetector);
    }

    /**
     * Returns the {@link FutureIgnoredDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFutureIgnored = false}
     * @since 0.10.0
     *
     * @return the {@link FutureIgnoredDetector} for the active {@code @AsyncTest} context
     */
    public static FutureIgnoredDetector futureIgnoredDetector() {
        return require("detectFutureIgnored", c -> c.futureIgnoredDetector);
    }

    /**
     * Returns the {@link ExplicitGcDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExplicitGc = false}
     * @since 0.10.0
     *
     * @return the {@link ExplicitGcDetector} for the active {@code @AsyncTest} context
     */
    public static ExplicitGcDetector explicitGcDetector() {
        return require("detectExplicitGc", c -> c.explicitGcDetector);
    }

    /**
     * Returns the {@link DeprecatedThreadApiDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDeprecatedThreadApi = false}
     * @since 0.10.0
     *
     * @return the {@link DeprecatedThreadApiDetector} for the active {@code @AsyncTest} context
     */
    public static DeprecatedThreadApiDetector deprecatedThreadApiDetector() {
        return require("detectDeprecatedThreadApi", c -> c.deprecatedThreadApiDetector);
    }

    /**
     * Returns the {@link SharedXmlParserDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedXmlParser = false}
     * @since 0.10.0
     *
     * @return the {@link SharedXmlParserDetector} for the active {@code @AsyncTest} context
     */
    public static SharedXmlParserDetector sharedXmlParserDetector() {
        return require("detectSharedXmlParser", c -> c.sharedXmlParserDetector);
    }

    /**
     * Returns the {@link BoxedPrimitiveLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBoxedPrimitiveLock = false}
     * @since 0.10.0
     *
     * @return the {@link BoxedPrimitiveLockDetector} for the active {@code @AsyncTest} context
     */
    public static BoxedPrimitiveLockDetector boxedPrimitiveLockDetector() {
        return require("detectBoxedPrimitiveLock", c -> c.boxedPrimitiveLockDetector);
    }

    /**
     * Returns the {@link SharedTimeZoneDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedTimeZone = false}
     * @since 0.10.0
     *
     * @return the {@link SharedTimeZoneDetector} for the active {@code @AsyncTest} context
     */
    public static SharedTimeZoneDetector sharedTimeZoneDetector() {
        return require("detectSharedTimeZone", c -> c.sharedTimeZoneDetector);
    }

    /**
     * Returns the {@link UncaughtExceptionHandlerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectUncaughtExceptionHandler = false}
     * @since 0.10.0
     *
     * @return the {@link UncaughtExceptionHandlerDetector} for the active {@code @AsyncTest} context
     */
    public static UncaughtExceptionHandlerDetector uncaughtExceptionHandlerDetector() {
        return require("detectUncaughtExceptionHandler", c -> c.uncaughtExceptionHandlerDetector);
    }

    // ---- Phase 13 accessors (1.0.0+) ----

    /**
     * Returns the {@link DaemonThreadHygieneDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDaemonThreadHygiene = false}
     * @since 1.6.0
     *
     * @return the {@link DaemonThreadHygieneDetector} for the active {@code @AsyncTest} context
     */
    public static DaemonThreadHygieneDetector daemonThreadHygieneDetector() {
        return require("detectDaemonThreadHygiene", c -> c.daemonThreadHygieneDetector);
    }

    /**
     * Returns the {@link NotifyWithoutMonitorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectNotifyWithoutMonitor = false}
     * @since 1.6.0
     *
     * @return the {@link NotifyWithoutMonitorDetector} for the active {@code @AsyncTest} context
     */
    public static NotifyWithoutMonitorDetector notifyWithoutMonitorDetector() {
        return require("detectNotifyWithoutMonitor", c -> c.notifyWithoutMonitorDetector);
    }

    /**
     * Returns the {@link SharedSecureRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedSecureRandom = false}
     * @since 1.6.0
     *
     * @return the {@link SharedSecureRandomDetector} for the active {@code @AsyncTest} context
     */
    public static SharedSecureRandomDetector sharedSecureRandomDetector() {
        return require("detectSharedSecureRandom", c -> c.sharedSecureRandomDetector);
    }

    /**
     * Returns the {@link WeakHashMapSharedDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWeakHashMapShared = false}
     * @since 1.6.0
     *
     * @return the {@link WeakHashMapSharedDetector} for the active {@code @AsyncTest} context
     */
    public static WeakHashMapSharedDetector weakHashMapSharedDetector() {
        return require("detectWeakHashMapShared", c -> c.weakHashMapSharedDetector);
    }

    /**
     * Returns the {@link JdbcConnectionSharedDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectJdbcConnectionShared = false}
     * @since 1.6.0
     *
     * @return the {@link JdbcConnectionSharedDetector} for the active {@code @AsyncTest} context
     */
    public static JdbcConnectionSharedDetector jdbcConnectionSharedDetector() {
        return require("detectJdbcConnectionShared", c -> c.jdbcConnectionSharedDetector);
    }

    /**
     * Returns the {@link SharedStatefulCryptoDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedStatefulCrypto = false}
     * @since 1.7.0
     *
     * @return the {@link SharedStatefulCryptoDetector} for the active {@code @AsyncTest} context
     */
    public static SharedStatefulCryptoDetector sharedStatefulCryptoDetector() {
        return require("detectSharedStatefulCrypto", c -> c.sharedStatefulCryptoDetector);
    }

    /**
     * Returns the {@link NonAtomicConcurrentMapUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentMapCheckThenAct = false}
     * @since 1.7.0
     *
     * @return the {@link NonAtomicConcurrentMapUpdateDetector} for the active {@code @AsyncTest} context
     */
    public static NonAtomicConcurrentMapUpdateDetector nonAtomicConcurrentMapUpdateDetector() {
        return require("detectConcurrentMapCheckThenAct", c -> c.nonAtomicConcurrentMapUpdateDetector);
    }

    /**
     * Returns the {@link SharedDeflaterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedDeflater = false}
     * @since 1.7.0
     *
     * @return the {@link SharedDeflaterDetector} for the active {@code @AsyncTest} context
     */
    public static SharedDeflaterDetector sharedDeflaterDetector() {
        return require("detectSharedDeflater", c -> c.sharedDeflaterDetector);
    }

    /**
     * Returns the {@link ThisEscapeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThisEscape = false}
     * @since 1.7.0
     *
     * @return the {@link ThisEscapeDetector} for the active {@code @AsyncTest} context
     */
    public static ThisEscapeDetector thisEscapeDetector() {
        return require("detectThisEscape", c -> c.thisEscapeDetector);
    }

    /**
     * Returns the {@link ThreadLocalRandomMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalRandomMisuse = false}
     * @since 1.7.0
     *
     * @return the {@link ThreadLocalRandomMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadLocalRandomMisuseDetector threadLocalRandomMisuseDetector() {
        return require("detectThreadLocalRandomMisuse", c -> c.threadLocalRandomMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureObtrudeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureObtrudeAbuse = false}
     * @since 1.7.0
     *
     * @return the {@link CompletableFutureObtrudeDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureObtrudeDetector completableFutureObtrudeDetector() {
        return require("detectCompletableFutureObtrudeAbuse", c -> c.completableFutureObtrudeDetector);
    }

    /**
     * Returns the {@link SpuriousWakeupDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSpuriousWakeupHazard = false}
     * @since 1.7.0
     *
     * @return the {@link SpuriousWakeupDetector} for the active {@code @AsyncTest} context
     */
    public static SpuriousWakeupDetector spuriousWakeupHazardDetector() {
        return require("detectSpuriousWakeupHazard", c -> c.spuriousWakeupHazardDetector);
    }

    /**
     * Returns the {@link LockUpgradeDeadlockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockUpgradeDeadlock = false}
     * @since 1.7.0
     *
     * @return the {@link LockUpgradeDeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static LockUpgradeDeadlockDetector lockUpgradeDeadlockDetector() {
        return require("detectLockUpgradeDeadlock", c -> c.lockUpgradeDeadlockDetector);
    }

    /**
     * Returns the {@link TryLockMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectTryLockMisuse = false}
     * @since 1.7.0
     *
     * @return the {@link TryLockMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static TryLockMisuseDetector tryLockMisuseDetector() {
        return require("detectTryLockMisuse", c -> c.tryLockMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureBlockingCallbackDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCFBlockingCallback = false}
     * @since 1.7.0
     *
     * @return the {@link CompletableFutureBlockingCallbackDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureBlockingCallbackDetector cfBlockingCallbackDetector() {
        return require("detectCFBlockingCallback", c -> c.cfBlockingCallbackDetector);
    }

    /**
     * Returns the {@link StableValueMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStableValueMisuse = false}
     * @since 1.7.0
     *
     * @return the {@link StableValueMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static StableValueMisuseDetector stableValueMisuseDetector() {
        return require("detectStableValueMisuse", c -> c.stableValueMisuseDetector);
    }

    /**
     * Returns the {@link StructuredTaskScopeMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStructuredTaskScopeMisuse = false}
     * @since 1.7.0
     *
     * @return the {@link StructuredTaskScopeMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static StructuredTaskScopeMisuseDetector structuredTaskScopeMisuseDetector() {
        return require("detectStructuredTaskScopeMisuse", c -> c.structuredTaskScopeMisuseDetector);
    }

    /**
     * Returns the {@link GathererConcurrencyMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectGathererConcurrencyMisuse = false}
     * @since 1.7.0
     *
     * @return the {@link GathererConcurrencyMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static GathererConcurrencyMisuseDetector gathererConcurrencyMisuseDetector() {
        return require("detectGathererConcurrencyMisuse", c -> c.gathererConcurrencyMisuseDetector);
    }

    /**
     * Returns the {@link SharedByteBufferDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedByteBuffer = false}
     * @since 1.7.0
     *
     * @return the {@link SharedByteBufferDetector} for the active {@code @AsyncTest} context
     */
    public static SharedByteBufferDetector sharedByteBufferDetector() {
        return require("detectSharedByteBuffer", c -> c.sharedByteBufferDetector);
    }

    /**
     * Returns the {@link SharedCharsetCoderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedCharsetCoder = false}
     * @since 1.7.0
     *
     * @return the {@link SharedCharsetCoderDetector} for the active {@code @AsyncTest} context
     */
    public static SharedCharsetCoderDetector sharedCharsetCoderDetector() {
        return require("detectSharedCharsetCoder", c -> c.sharedCharsetCoderDetector);
    }

    /**
     * Returns the {@link SharedChecksumDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedChecksum = false}
     * @since 1.7.0
     *
     * @return the {@link SharedChecksumDetector} for the active {@code @AsyncTest} context
     */
    public static SharedChecksumDetector sharedChecksumDetector() {
        return require("detectSharedChecksum", c -> c.sharedChecksumDetector);
    }

    /**
     * Returns the {@link FileChannelPositionRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFileChannelPositionRace = false}
     * @since 1.7.0
     *
     * @return the {@link FileChannelPositionRaceDetector} for the active {@code @AsyncTest} context
     */
    public static FileChannelPositionRaceDetector fileChannelPositionRaceDetector() {
        return require("detectFileChannelPositionRace", c -> c.fileChannelPositionRaceDetector);
    }

    /**
     * Returns the {@link SharedIteratorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedIterator = false}
     * @since 1.7.0
     *
     * @return the {@link SharedIteratorDetector} for the active {@code @AsyncTest} context
     */
    public static SharedIteratorDetector sharedIteratorDetector() {
        return require("detectSharedIterator", c -> c.sharedIteratorDetector);
    }

    /**
     * Returns the {@link HighContentionAtomicDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectHighContentionAtomic = false}
     * @since 1.7.0
     *
     * @return the {@link HighContentionAtomicDetector} for the active {@code @AsyncTest} context
     */
    public static HighContentionAtomicDetector highContentionAtomicDetector() {
        return require("detectHighContentionAtomic", c -> c.highContentionAtomicDetector);
    }

    /**
     * Returns the {@link SharedJsonMapperReconfigDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedJsonMapperReconfig = false}
     * @since 1.7.0
     *
     * @return the {@link SharedJsonMapperReconfigDetector} for the active {@code @AsyncTest} context
     */
    public static SharedJsonMapperReconfigDetector sharedJsonMapperReconfigDetector() {
        return require("detectSharedJsonMapperReconfig", c -> c.sharedJsonMapperReconfigDetector);
    }

    /**
     * Returns the {@link LazyConstantMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLazyConstantMisuse = false}
     * @since 1.7.0
     *
     * @return the {@link LazyConstantMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static LazyConstantMisuseDetector lazyConstantMisuseDetector() {
        return require("detectLazyConstantMisuse", c -> c.lazyConstantMisuseDetector);
    }

    /**
     * Returns the {@link FinalFieldMutationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFinalFieldMutation = false}
     * @since 1.7.0
     *
     * @return the {@link FinalFieldMutationDetector} for the active {@code @AsyncTest} context
     */
    public static FinalFieldMutationDetector finalFieldMutationDetector() {
        return require("detectFinalFieldMutation", c -> c.finalFieldMutationDetector);
    }

    /**
     * Returns the {@link SharedKdfDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedKdf = false}
     * @since 1.7.0
     *
     * @return the {@link SharedKdfDetector} for the active {@code @AsyncTest} context
     */
    public static SharedKdfDetector sharedKdfDetector() {
        return require("detectSharedKdf", c -> c.sharedKdfDetector);
    }

    /**
     * Returns the {@link LatchMisuseDetector} for the current test.
     *
     * <p>Register each latch with {@code registerLatch(latch, name, initialCount)} and record
     * {@code recordAwait} / {@code recordCountDown} around its use; the detector is analysed
     * with the rest at end of test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLatchMisuse = false}
     * @since 1.7.0
     *
     * @return the {@link LatchMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static LatchMisuseDetector latchMisuseDetector() {
        return require("detectLatchMisuse", c -> c.latchMisuseDetector);
    }

    /**
     * Returns the {@link ExecutorDeadlockDetector} for the current test.
     *
     * <p>Register each executor with {@code registerExecutor(executor, name, maxThreads)} and
     * record {@code recordTaskSubmitted} / {@code recordTaskStarted} /
     * {@code recordWaitingOnSibling} / {@code recordTaskCompleted} around its tasks.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExecutorDeadlock = false}
     * @since 1.7.0
     *
     * @return the {@link ExecutorDeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static ExecutorDeadlockDetector executorDeadlockDetector() {
        return require("detectExecutorDeadlock", c -> c.executorDeadlockDetector);
    }

    /**
     * Returns the {@link FutureBlockingDetector} for the current test.
     *
     * <p>Register each executor with {@code registerExecutor(executor, name, maxThreads)} and
     * record {@code recordBlockingWait} where a task blocks on a {@code Future} from the same
     * pool.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFutureBlocking = false}
     * @since 1.7.0
     *
     * @return the {@link FutureBlockingDetector} for the active {@code @AsyncTest} context
     */
    public static FutureBlockingDetector futureBlockingDetector() {
        return require("detectFutureBlocking", c -> c.futureBlockingDetector);
    }

    /**
     * Returns the {@link FlowPublisherConcurrencyDetector} for the current test.
     *
     * <p>Bracket each {@code onNext} delivery with {@code recordNextStart} /
     * {@code recordNextEnd}, record demand with {@code recordRequest}, and terminal
     * signals with {@code recordComplete} / {@code recordError}.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFlowPublisherConcurrency = false}
     * @since 1.7.1
     *
     * @return the {@link FlowPublisherConcurrencyDetector} for the active {@code @AsyncTest} context
     */
    public static FlowPublisherConcurrencyDetector flowPublisherConcurrencyDetector() {
        return require("detectFlowPublisherConcurrency", c -> c.flowPublisherConcurrencyDetector);
    }

    /**
     * Returns the {@link ConfinedArenaThreadEscapeDetector} for the current test.
     *
     * <p>Register the arena with {@code recordArena}, each allocation with
     * {@code recordAllocation}, and every touch with {@code recordAccess}; the detector asks the
     * JVM whether the accessing thread is allowed to touch the segment.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConfinedArenaThreadEscape = false}
     * @since 1.8.0
     *
     * @return the {@link ConfinedArenaThreadEscapeDetector} for the active {@code @AsyncTest} context
     */
    public static ConfinedArenaThreadEscapeDetector confinedArenaThreadEscapeDetector() {
        return require("detectConfinedArenaThreadEscape", c -> c.confinedArenaThreadEscapeDetector);
    }

    /**
     * Returns the {@link SharedMemorySegmentRaceDetector} for the current test.
     *
     * <p>Record each access with its byte offset and length. Pass the optional {@code guard}
     * label naming the monitor held during the access, and overlapping accesses that agree on a
     * guard are treated as synchronized rather than reported.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedMemorySegmentRace = false}
     * @since 1.8.0
     *
     * @return the {@link SharedMemorySegmentRaceDetector} for the active {@code @AsyncTest} context
     */
    public static SharedMemorySegmentRaceDetector sharedMemorySegmentRaceDetector() {
        return require("detectSharedMemorySegmentRace", c -> c.sharedMemorySegmentRaceDetector);
    }

    /**
     * Returns the {@link VarHandleNonAtomicUpdateDetector} for the current test.
     *
     * <p>Record reads with {@code recordGet}, writes with {@code recordSet}, and the CAS family
     * with {@code recordAtomicUpdate}. A get followed by a set with no atomic update between
     * them is a lost update.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVarHandleNonAtomicUpdate = false}
     * @since 1.8.0
     *
     * @return the {@link VarHandleNonAtomicUpdateDetector} for the active {@code @AsyncTest} context
     */
    public static VarHandleNonAtomicUpdateDetector varHandleNonAtomicUpdateDetector() {
        return require("detectVarHandleNonAtomicUpdate", c -> c.varHandleNonAtomicUpdateDetector);
    }

    /**
     * Returns the {@link RecordMutableComponentLeakDetector} for the current test.
     *
     * <p>Call {@code recordShared} from every thread that touches the record. The first call
     * fingerprints each component, so a component whose contents change during the run is
     * reported as an observed mutation rather than a structural risk.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectRecordMutableComponentLeak = false}
     * @since 1.8.0
     *
     * @return the {@link RecordMutableComponentLeakDetector} for the active {@code @AsyncTest} context
     */
    public static RecordMutableComponentLeakDetector recordMutableComponentLeakDetector() {
        return require("detectRecordMutableComponentLeak", c -> c.recordMutableComponentLeakDetector);
    }

    /**
     * Returns the {@link StaticInitDeadlockDetector} for the current test.
     *
     * <p>Bracket a static initializer with {@code recordInitStart} / {@code recordInitEnd} and
     * announce each class it touches with {@code recordInitRequest}. Without any instrumentation
     * the detector still samples live threads for {@code <clinit>} frames.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStaticInitDeadlock = false}
     * @since 1.8.0
     *
     * @return the {@link StaticInitDeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static StaticInitDeadlockDetector staticInitDeadlockDetector() {
        return require("detectStaticInitDeadlock", c -> c.staticInitDeadlockDetector);
    }

    /**
     * Returns the {@link VirtualThreadPoolingDetector} for the current test.
     *
     * <p>Register executors with {@code registerExecutor} and call {@code recordTaskExecution}
     * once from inside each task; the detector flags pooled executors that manufacture virtual
     * threads and virtual threads observed running more than one task.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadPooling = false}
     * @since 1.8.0
     *
     * @return the {@link VirtualThreadPoolingDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadPoolingDetector virtualThreadPoolingDetector() {
        return require("detectVirtualThreadPooling", c -> c.virtualThreadPoolingDetector);
    }

    /**
     * Returns the {@link PlatformThreadPerTaskDetector} for the current test.
     *
     * <p>Record each thread the test creates with {@code recordThreadCreated}, and register
     * executors with {@code registerExecutor}; the detector flags platform-thread churn and
     * thread-per-task executors backed by platform threads.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPlatformThreadPerTask = false}
     * @since 1.8.0
     *
     * @return the {@link PlatformThreadPerTaskDetector} for the active {@code @AsyncTest} context
     */
    public static PlatformThreadPerTaskDetector platformThreadPerTaskDetector() {
        return require("detectPlatformThreadPerTask", c -> c.platformThreadPerTaskDetector);
    }

    /**
     * Returns the {@link SharedSplittableRandomDetector} for the current test.
     *
     * <p>Register generators with {@code registerGenerator} and record each use with
     * {@code recordAccess}; the detector flags SplittableRandom and JEP 356 generators
     * accessed from more than one thread.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedSplittableRandom = false}
     * @since 1.8.0
     *
     * @return the {@link SharedSplittableRandomDetector} for the active {@code @AsyncTest} context
     */
    public static SharedSplittableRandomDetector sharedSplittableRandomDetector() {
        return require("detectSharedSplittableRandom", c -> c.sharedSplittableRandomDetector);
    }

    /**
     * Returns the {@link CompletableFutureCompletionRaceDetector} for the current test.
     *
     * <p>Complete futures through {@code complete}/{@code completeExceptionally} on the detector,
     * or record the boolean each call returned with {@code recordCompletionAttempt}; the detector
     * reports the attempts that lost the race and had their value or exception discarded.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureCompletionRace = false}
     * @since 1.9.5
     *
     * @return the {@link CompletableFutureCompletionRaceDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCompletionRaceDetector cfCompletionRaceDetector() {
        return require("detectCompletableFutureCompletionRace", c -> c.completableFutureCompletionRaceDetector);
    }

    /**
     * Returns the {@link CompletableFutureCancellationPropagationDetector} for the current test.
     *
     * <p>Bracket stage bodies with {@code recordWorkStarted}/{@code recordWorkCompleted} and cancel
     * through the detector's {@code cancel}; it reports stage work that ran to completion after
     * the cancel, and {@code cancel(true)} calls on a type that never interrupts. Label each
     * pipeline instance separately under {@code @AsyncTest}, so one worker's cancel is not matched
     * against another worker's stages.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureCancellationPropagation = false}
     * @since 1.9.5
     *
     * @return the {@link CompletableFutureCancellationPropagationDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCancellationPropagationDetector cfCancellationPropagationDetector() {
        return require("detectCompletableFutureCancellationPropagation", c -> c.completableFutureCancellationPropagationDetector);
    }

    /**
     * Returns the {@link CompletableFutureCombinatorMisuseDetector} for the current test.
     *
     * <p>Register combinator futures with {@code recordCombinator}, their constituents with
     * {@code recordConstituentCompleted} and each read with {@code recordAwait}; the detector
     * reports groups the code moved past before they had finished.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureCombinatorMisuse = false}
     * @since 1.9.5
     *
     * @return the {@link CompletableFutureCombinatorMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCombinatorMisuseDetector cfCombinatorMisuseDetector() {
        return require("detectCompletableFutureCombinatorMisuse", c -> c.completableFutureCombinatorMisuseDetector);
    }

    /**
     * Returns the {@link LambdaLostUpdateDetector} for the current test.
     *
     * <p>Record each read-modify-write of a captured variable with {@code recordReadModifyWrite},
     * passing the value read and the value written; the detector reports the updates it can prove
     * were lost: two threads read the same value first, and no serial order of the recorded updates
     * could explain that.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLambdaLostUpdate = false}
     * @since 1.9.5
     *
     * @return the {@link LambdaLostUpdateDetector} for the active {@code @AsyncTest} context
     */
    public static LambdaLostUpdateDetector lambdaLostUpdateDetector() {
        return require("detectLambdaLostUpdate", c -> c.lambdaLostUpdateDetector);
    }

    /**
     * Returns the {@link VirtualThreadResourceSaturationDetector} for the current test.
     *
     * <p>Declare the bounded resource with {@code registerResource(name, capacity)}, then bracket
     * each acquisition with {@code recordAcquireStart} and {@code recordAcquired}, or
     * {@code recordAcquireAbandoned} when the wait gives up; the detector reports a fan-out in which
     * more virtual threads waited at once than the resource can serve.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadResourceSaturation = false}
     * @since 1.9.5
     *
     * @return the {@link VirtualThreadResourceSaturationDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadResourceSaturationDetector vthreadResourceSaturationDetector() {
        return require("detectVirtualThreadResourceSaturation", c -> c.virtualThreadResourceSaturationDetector);
    }

    /**
     * Returns the {@link VirtualThreadMonitorSerializationDetector} for the current test.
     *
     * <p>Call {@code recordMonitorEnter} immediately before the {@code synchronized} block and
     * {@code recordMonitorAcquired} inside it; the detector reports the peak number of virtual
     * threads queued at once, alongside the deepest queue overall and the distinct virtual waiters.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVirtualThreadMonitorSerialization = false}
     * @since 1.9.5
     *
     * @return the {@link VirtualThreadMonitorSerializationDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadMonitorSerializationDetector vthreadMonitorSerializationDetector() {
        return require("detectVirtualThreadMonitorSerialization", c -> c.virtualThreadMonitorSerializationDetector);
    }

    /**
     * Returns the {@link ThreadLocalCacheDegradationDetector} for the current test.
     *
     * <p>Record the value each thread obtains with {@code recordCachedValue}; the detector counts
     * distinct instances by identity and reports a key that produced one per virtual thread
     * instead of one per pooled worker.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalCacheDegradation = false}
     * @since 1.9.5
     *
     * @return the {@link ThreadLocalCacheDegradationDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadLocalCacheDegradationDetector threadLocalCacheDegradationDetector() {
        return require("detectThreadLocalCacheDegradation", c -> c.threadLocalCacheDegradationDetector);
    }

    /**
     * Returns the {@link ScopeJoinerMisuseDetector} for the current test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScopeJoinerMisuse = false}
     * @since 1.9.7
     *
     * @return the {@link ScopeJoinerMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static ScopeJoinerMisuseDetector scopeJoinerMisuseDetector() {
        return require("detectScopeJoinerMisuse", c -> c.scopeJoinerMisuseDetector);
    }

    /**
     * Returns the {@link ScopeConfigurationMisuseDetector} for the current test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScopeConfigurationMisuse = false}
     * @since 1.9.7
     *
     * @return the {@link ScopeConfigurationMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static ScopeConfigurationMisuseDetector scopeConfigurationMisuseDetector() {
        return require("detectScopeConfigurationMisuse", c -> c.scopeConfigurationMisuseDetector);
    }

    /**
     * Returns the {@link ScopeResultEscapeDetector} for the current test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScopeResultEscape = false}
     * @since 1.9.7
     *
     * @return the {@link ScopeResultEscapeDetector} for the active {@code @AsyncTest} context
     */
    public static ScopeResultEscapeDetector scopeResultEscapeDetector() {
        return require("detectScopeResultEscape", c -> c.scopeResultEscapeDetector);
    }

    /**
     * Returns the {@link LazyCollectionMisuseDetector} for the current test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLazyCollectionMisuse = false}
     * @since 1.9.7
     *
     * @return the {@link LazyCollectionMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static LazyCollectionMisuseDetector lazyCollectionMisuseDetector() {
        return require("detectLazyCollectionMisuse", c -> c.lazyCollectionMisuseDetector);
    }

    // ---- Phase 1 / Phase 3 detector accessors ----
    //
    // These seven detectors are fed by the runner as well as by the test body, and for a
    // long time only the runner could reach them: their instances were available solely
    // through the internal sharedXxx() methods below. Six of them expose recordXxx methods
    // written for a test body to call, so the API existed without a public door to it.
    // These are that door, and they behave like every other accessor on this class.

    /**
     * Returns the {@link DeadlockDetector} for the current test.
     *
     * <p>Mostly of interest for its instance {@code analyze()}, which reports the cycle the
     * runner found. The class also exposes {@code hasDeadlock()} and {@code printThreadDump()}
     * statically, which need no context.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDeadlocks = false}
     * @since 1.7.0
     *
     * @return the {@link DeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static DeadlockDetector deadlockDetector() {
        return require("detectDeadlocks", c -> c.registry.deadlockDetector);
    }

    /**
     * Returns the {@link VisibilityMonitor} for the current test.
     *
     * <p>Record each read or write of a field you suspect needs {@code volatile} with
     * {@code recordFieldAccess(fieldIdentifier, value)}; the monitor reports fields whose
     * observed value diverges between threads.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVisibility = false}
     * @since 1.7.0
     *
     * @return the {@link VisibilityMonitor} for the active {@code @AsyncTest} context
     */
    public static VisibilityMonitor visibilityMonitor() {
        return require("detectVisibility", AsyncTestContext::sharedVisibilityMonitor);
    }

    /**
     * Returns the {@link LivelockDetector} for the current test.
     *
     * <p>Call {@code captureSnapshot()} from inside a retry loop; the detector compares
     * successive snapshots and reports threads that stay runnable without progressing.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLivelocks = false}
     * @since 1.7.0
     *
     * @return the {@link LivelockDetector} for the active {@code @AsyncTest} context
     */
    public static LivelockDetector livelockDetector() {
        return require("detectLivelocks", AsyncTestContext::sharedLivelockDetector);
    }

    /**
     * Returns the {@link RaceConditionDetector} for the current test.
     *
     * <p>Record accesses to shared state with {@code recordFieldRead(owner, field)} and
     * {@code recordFieldWrite(owner, field)}; the detector reports unsynchronised
     * read/write pairs observed from different threads.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectRaceConditions = false}
     * @since 1.7.0
     *
     * @return the {@link RaceConditionDetector} for the active {@code @AsyncTest} context
     */
    public static RaceConditionDetector raceConditionDetector() {
        return require("detectRaceConditions", AsyncTestContext::sharedRaceConditionDetector);
    }

    /**
     * Returns the {@link ThreadLocalMonitor} for the current test.
     *
     * <p>Bracket each {@code ThreadLocal} with {@code recordThreadLocalInit(tl, name)},
     * {@code recordThreadLocalAccess(tl)} and {@code recordThreadLocalCleanup(tl)}; the
     * monitor reports the ones never cleaned up on a thread that outlives the task.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalLeaks = false}
     * @since 1.7.0
     *
     * @return the {@link ThreadLocalMonitor} for the active {@code @AsyncTest} context
     */
    public static ThreadLocalMonitor threadLocalMonitor() {
        return require("detectThreadLocalLeaks", AsyncTestContext::sharedThreadLocalMonitor);
    }

    /**
     * Returns the {@link BusyWaitDetector} for the current test.
     *
     * <p>Call {@code recordLoopIteration()} from a spin loop and {@code recordYield()} where
     * it parks, or report a loop wholesale with {@code reportSpinLoop(description, iterations)}.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBusyWaiting = false}
     * @since 1.7.0
     *
     * @return the {@link BusyWaitDetector} for the active {@code @AsyncTest} context
     */
    public static BusyWaitDetector busyWaitDetector() {
        return require("detectBusyWaiting", AsyncTestContext::sharedBusyWaitDetector);
    }

    /**
     * Returns the {@link InterruptMonitor} for the current test.
     *
     * <p>Record {@code recordInterruptException(e)} in the catch block and
     * {@code recordInterruptRestored()} where the flag is put back; the monitor reports
     * interrupts that were caught and swallowed.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectInterruptMishandling = false}
     * @since 1.7.0
     *
     * @return the {@link InterruptMonitor} for the active {@code @AsyncTest} context
     */
    public static InterruptMonitor interruptMonitor() {
        return require("detectInterruptMishandling", AsyncTestContext::sharedInterruptMonitor);
    }

    // ---- Helper ----

    /**
     * Returns the {@link AtomicityValidator} for the current test.
     *
     * <p>Primarily intended for {@code se.deversity.asynctest.telemetry.TelemetryBridge},
     * which routes drained agent field-access events into this live detector so that
     * agent-captured accesses participate in the same cross-thread atomicity analysis as
     * manually recorded ones.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or
     *                               {@code detectAtomicityViolations = false}
     * @since 1.7.0
     *
     * @return the {@link AtomicityValidator} for the active {@code @AsyncTest} context
     */
    public static AtomicityValidator atomicityValidator() {
        return require("detectAtomicityViolations", c -> c.atomicityValidator);
    }

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
