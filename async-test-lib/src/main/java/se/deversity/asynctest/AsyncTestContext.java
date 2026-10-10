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
import se.deversity.asynctest.diagnostics.SelfGuard;
import se.deversity.asynctest.diagnostics.WorkerSlot;
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

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * @AsyncTest(threads = 4, includes = DetectorType.FALSE_SHARING)
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

    /**
     * The run of each thread a run's thread started through a woven {@code Thread.start}, by
     * thread id, which the JVM never reuses (#834). Weakly held, so a thread that outlives a run
     * that was never analysed cannot keep its context reachable; a run that is analysed stops
     * lending at once, and its entries go then.
     */
    private static final Map<Long, WeakReference<AsyncTestContext>> SPAWNED = new ConcurrentHashMap<>();

    /** A soft bound on {@link #SPAWNED} before entries of finished runs are swept. */
    private static final int SPAWNED_SWEEP_AT = 4096;

    /**
     * The run a pool thread works for while it runs a task or stage function a run's thread
     * handed over through a woven call (#834). Set and cleared around that one task, in a
     * {@code finally}, so it falls under the same symmetry rule as {@link #CURRENT}.
     */
    private static final ThreadLocal<AsyncTestContext> LENT = new ThreadLocal<>();

    /** Holds detector instances; extracted to keep this class small. */
    private final DetectorRegistry registry;

    /**
     * The round clock the lock-aware detectors judge sharing within. Bound to each worker in
     * {@link #install}, unbound in {@link #uninstall()}, advanced by {@link #markInvocationStart()}.
     * Thread-safe on its own (an atomic counter), so sharing it across the workers needs nothing
     * here.
     */
    private final SelfGuard.Scope sharingScope = new SelfGuard.Scope();

    /** Whether analysis has started, after which no thread is lent this run any more (#834). */
    private volatile boolean analysed;

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
     * {@code null} when {@link DetectorType#VISIBILITY} is not enabled.
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
     * {@code null} when {@link DetectorType#LIVELOCKS} is not enabled.
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
     * {@code null} when {@link DetectorType#RACE_CONDITIONS} is not enabled.
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
     * {@code null} when {@link DetectorType#THREAD_LOCAL_LEAKS} is not enabled.
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
     * {@code null} when {@link DetectorType#BUSY_WAITING} is not enabled.
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
     * {@code null} when {@link DetectorType#ATOMICITY_VIOLATIONS} is not enabled.
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
     * Internal: registry-backed {@link VirtualThreadPinningDetector} for this context, or
     * {@code null} when {@link DetectorType#VIRTUAL_THREAD_PINNING} is not enabled.
     *
     * <p>Same instance as the public {@link #virtualThreadPinningDetector()} accessor, and the
     * one {@link se.deversity.asynctest.diagnostics.JfrPinningStream} feeds; unlike that accessor
     * this one returns {@code null} instead of throwing when disabled.
     *
     * <p>Public only so {@code ConcurrencyRunner}, which lives in a different package, can call
     * it; not part of the stable public API.
     *
     * @return the shared {@link VirtualThreadPinningDetector} for this context, or {@code null} when it is disabled
     * @since 1.13.2
     */
    public @Nullable VirtualThreadPinningDetector sharedVirtualThreadPinningDetector() {
        return registry.virtualThreadPinningDetector;
    }

    /**
     * Internal: registry-backed {@link InterruptMonitor} for this context, or
     * {@code null} when {@link DetectorType#INTERRUPT_MISHANDLING} is not enabled.
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

    // ---- Work a run's threads hand over (#834) ----

    /**
     * {@return the run the calling thread works for, as the agent's hooks see it, or {@code null}}
     *
     * <p>A worker's installed context first; else the run that lent the thread a handed task it
     * is running; else the run whose thread started this one through a woven {@code Thread.start}.
     * A run whose analysis has started lends nothing. Reads only; installs nothing.
     *
     * <p>Only the shared-instance accessors use it, whose detectors judge overlap. A detector that
     * judges what never happened during the run, such as a latch never counted down, would read a
     * started thread that outlives the run as the run's own: the corpus agent-pair lane caught a
     * daemon thread waiting on a latch counted down after the run reported as latch misuse.
     */
    static @Nullable AsyncTestContext agentContext() {
        AsyncTestContext context = CURRENT.get();
        if (context != null) {
            return context;
        }
        context = LENT.get();
        if (context != null) {
            return context;
        }
        if (SPAWNED.isEmpty()) {
            return null;
        }
        WeakReference<AsyncTestContext> spawnedBy = SPAWNED.get(Thread.currentThread().threadId());
        context = spawnedBy == null ? null : spawnedBy.get();
        return context == null || context.analysed ? null : context;
    }

    /**
     * Called on the starting thread when a woven {@code Thread.start} is about to start
     * {@code child}: the child works for the starting thread's run, if it has one (#834).
     *
     * @param child the thread about to start
     */
    static void threadSpawned(Thread child) {
        AsyncTestContext context = agentContext();
        if (context == null || context.analysed) {
            return;
        }
        if (SPAWNED.size() >= SPAWNED_SWEEP_AT) {
            SPAWNED.values().removeIf(entry -> {
                AsyncTestContext run = entry.get();
                return run == null || run.analysed;
            });
        }
        SPAWNED.put(child.threadId(), new WeakReference<>(context));
        SelfGuard.Scope.spawned(child, context.sharingScope);
    }

    /**
     * Lends {@code context} to the calling pool thread for one handed task, unless it is a worker
     * with a context of its own. Pair with {@link #restoreLent} in a {@code finally} (#834).
     *
     * @param context the run the task was handed over in, {@code null} for none
     * @return what the thread was lent before, which {@link #restoreLent} puts back
     */
    static @Nullable AsyncTestContext lend(@Nullable AsyncTestContext context) {
        AsyncTestContext before = LENT.get();
        if (context != null && !context.analysed && CURRENT.get() == null) {
            LENT.set(context);
            SelfGuard.Scope.lend(context.sharingScope);
        }
        return before;
    }

    /**
     * Puts back what {@link #lend} returned, ending the loan it made.
     *
     * @param before the run the thread was lent before the task, {@code null} for none
     */
    static void restoreLent(@Nullable AsyncTestContext before) {
        if (before == null) {
            LENT.remove();
            SelfGuard.Scope.lend(null);
        } else {
            LENT.set(before);
            SelfGuard.Scope.lend(before.sharingScope);
        }
    }

    /** Stops lending this run to the threads its threads started, once its analysis starts. */
    private void stopLending() {
        analysed = true;
        SPAWNED.values().removeIf(entry -> {
            AsyncTestContext run = entry.get();
            return run == null || run == this; // NOPMD CompareObjectsWithEquals - this run, by identity
        });
        SelfGuard.Scope.forgetSpawned(sharingScope);
    }

    // ---- Lifecycle (called by ConcurrencyRunner) ----

    /**
     * Installs {@code ctx} into the calling thread's ThreadLocal.
     *
     * @param ctx the context to bind to the calling thread; must be paired with an {@code uninstall()} in a {@code finally}
     */
    @AICallersOnly({"se.deversity.asynctest.runner.ConcurrencyRunner"})
    public static void install(AsyncTestContext ctx) {
        SelfGuard.Scope.bind(ctx.sharingScope);
        CURRENT.set(ctx);
    }

    /**
     * Installs {@code ctx} and the runner's {@link WorkerSlot} for this body execution. Both are
     * cleared by {@link #uninstall()}, under the same symmetry rule, as is the context's sharing
     * scope, which either {@code install} binds.
     *
     * @param ctx        the context to bind to the calling thread
     * @param workerSlot the worker's index within its round, not negative
     * @since 1.12.2
     */
    @AICallersOnly({"se.deversity.asynctest.runner.ConcurrencyRunner"})
    public static void install(AsyncTestContext ctx, int workerSlot) {
        WorkerSlot.set(workerSlot);
        SelfGuard.Scope.bind(ctx.sharingScope);
        CURRENT.set(ctx);
    }

    /**
     * Removes the context from the calling thread's ThreadLocal.
     */
    @AIIdempotent(reason = "ThreadLocal.remove() is documented as a no-op when the thread has no value set; the install/uninstall symmetry rule (CLAUDE.md) tolerates extra uninstalls. ConcurrencyRunner relies on this in its outermost-finally cleanup.")
    public static void uninstall() {
        // Both ThreadLocals go together. A declared lock that outlived its invocation would be
        // intersected into the next round's lockset and could silence a real finding there, so
        // the symmetry rule covers this one exactly as it covers CURRENT. The same holds for the
        // sharing scope: one left bound would file this thread's next records under a finished run.
        HeldLocks.clear();
        WorkerSlot.clear();
        SelfGuard.Scope.unbind();
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
     * lock here is what tells the detectors otherwise (the example records through
     * {@code DetectorType.SHARED_COLLECTIONS}, which the test names in {@code includes}):
     *
     * <pre>{@code
     * try (var held = AsyncTestContext.holdingLock(cacheLock)) {
     *     cacheLock.lock();
     *     try {
     *         AsyncTestContext.sharedCollectionDetector().recordWrite(cache, "cache", "put");
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
     * Declares that the calling thread has just taken sole ownership of {@code instance}, so that
     * detectors judge the accesses before and after as a hand-off rather than as sharing.
     *
     * <p>A pool of non-thread-safe instances is correct when each checkout gives one thread the
     * instance alone, and a {@code Shared*} detector otherwise sees only that several threads
     * touched it. With the agent attached ({@code collections = true}) a take out of a woven
     * {@code BlockingQueue} or {@code Queue}, or a swap out of an atomic slot, is recognised on
     * its own. A checkout the weaver never sees (a pool library's own code, a hand-written
     * semaphore) is declared here, right after the checkout returns:
     *
     * <pre>{@code
     * MessageDigest md = pool.checkout();
     * AsyncTestContext.ownershipTaken(md);
     * try {
     *     md.update(data);
     * } finally {
     *     pool.release(md);
     * }
     * }</pre>
     *
     * <p>A pool that hands out a wrapper around the instance needs the declaration even with the
     * agent attached, unless its take is also a happens-before edge, as a woven
     * {@code BlockingQueue}'s is. The woven take names the wrapper, while the detector tracks the
     * instance inside it, so behind a plain collection and the pool's own lock nothing hands the
     * instance over (#747). Declare the instance the detector tracks, not the wrapper:
     *
     * <pre>{@code
     * DigestHolder holder = pool.checkout(); // an ArrayDeque behind the pool's monitor
     * AsyncTestContext.ownershipTaken(holder.digest());
     * }</pre>
     *
     * <p>Only the declaring thread's later accesses start a new owner. An access by the previous
     * owner after the declaration is still reported, because the instance then has two owners at
     * once. Safe outside a run, where it does nothing.
     *
     * @param instance the instance the calling thread now owns; {@code null} is ignored
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL, since = "1.12.3")
    public static void ownershipTaken(@Nullable Object instance) {
        SelfGuard.Scope.ownershipTaken(instance);
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
     * Called by {@code ConcurrencyRunner} at the start of each invocation round, after the
     * previous round's workers have all finished, so the detectors that count per round can close
     * the round in progress. Touches only this context's own detector instances, never the
     * {@code ThreadLocal}, so install/uninstall symmetry is unaffected.
     *
     * <p>Runner-only: public because {@code ConcurrencyRunner} is in another package, not part of
     * the test-author API. It runs on the runner's thread, which never has a run's context
     * installed; called from a test body it throws {@link IllegalStateException} (#947).
     *
     * @since 1.9.8
     */
    public void markInvocationStart() {
        requireRunnerThread("markInvocationStart");
        // Every lock-aware detector (the Shared* family and the others built on SelfGuard) judges
        // sharing within one round; this is the round boundary they read.
        sharingScope.markInvocationStart();
        if (sharedCollectionDetector != null) {
            sharedCollectionDetector.markInvocationStart();
        }
        if (registry.threadLocalMonitor != null) {
            registry.threadLocalMonitor.markInvocationStart();
        }
        if (registry.atomicNonAtomicUpdateDetector != null) {
            registry.atomicNonAtomicUpdateDetector.markInvocationStart();
        }
        if (registry.busyWaitDetector != null) {
            registry.busyWaitDetector.markInvocationStart();
        }
        if (registry.sharedMemorySegmentRaceDetector != null) {
            registry.sharedMemorySegmentRaceDetector.markInvocationStart();
        }
        if (completableFutureCancellationPropagationDetector != null) {
            completableFutureCancellationPropagationDetector.markInvocationStart();
        }
        if (registry.lockDowngradeDetector != null) {
            registry.lockDowngradeDetector.markInvocationStart();
        }
        // Optimistic reads and failed validations a pooled worker left open; the next round's
        // lock acquisition on the same thread must not read as their fallback (#588).
        if (stampedLockDetector != null) {
            stampedLockDetector.markInvocationStart();
        }
        // An await a pooled worker recorded and never exited belongs to its round; its await in
        // the next round is a new wait, not the same one (#593).
        if (conditionVariableDetector != null) {
            conditionVariableDetector.markInvocationStart();
        }
        // In-flight computations a thrown supplier or mapping function abandoned. Worker threads
        // are pooled, so without this the stale entry follows the thread into the next round and
        // reads as reentrancy or as recursion there (#498).
        if (lazyConstantMisuseDetector != null) {
            lazyConstantMisuseDetector.markInvocationStart();
        }
        // An integration a pooled worker opened and never exited belongs to its round; another
        // thread entering the same gatherer state next round is not an overlap with it (#846).
        if (gathererConcurrencyMisuseDetector != null) {
            gathererConcurrencyMisuseDetector.markInvocationStart();
        }
        if (lazyCollectionMisuseDetector != null) {
            lazyCollectionMisuseDetector.markInvocationStart();
        }
        if (stableValueMisuseDetector != null) {
            stableValueMisuseDetector.markInvocationStart();
        }
        if (concurrentMapComputeRecursionDetector != null) {
            concurrentMapComputeRecursionDetector.markInvocationStart();
        }
        // A predicate check confirms only a wait from its own round; a pooled worker's check in
        // the next round is that round's own test before its wait, not a re-test (#635).
        if (missedSignalDetector != null) {
            missedSignalDetector.markInvocationStart();
        }
        // An unsignalled wait return is excused only by a second wait from the same thread; a
        // pooled worker's wait in the next round is a fresh body execution, not that re-check (#590).
        if (wakeupDetector != null) {
            wakeupDetector.markInvocationStart();
        }
        // Two threads count as sharing a subject only inside one round: with virtual threads each
        // body execution has a fresh thread id, so ids gathered across rounds that never overlap
        // would read as sharing, and the same body on one pooled platform thread would not.
        if (sharedSecureRandomDetector != null) {
            sharedSecureRandomDetector.markInvocationStart();
        }
        if (highContentionAtomicDetector != null) {
            highContentionAtomicDetector.markInvocationStart();
        }
        if (recordMutableComponentLeakDetector != null) {
            recordMutableComponentLeakDetector.markInvocationStart();
        }
        if (finalFieldMutationDetector != null) {
            finalFieldMutationDetector.markInvocationStart();
        }
        if (lambdaLostUpdateDetector != null) {
            lambdaLostUpdateDetector.markInvocationStart();
        }
        if (lazyInitRaceDetector != null) {
            lazyInitRaceDetector.markInvocationStart();
        }
        // A change recorded after a compare-and-set can still be an A-B-A that it missed, until
        // the round ends; a later round's changes came after it (#779).
        if (abaProblemDetector != null) {
            abaProblemDetector.markInvocationStart();
        }
        // A pooled worker's seek slot would otherwise hold its channel until that worker seeks
        // again, if it ever does (#831).
        if (fileChannelPositionRaceDetector != null) {
            fileChannelPositionRaceDetector.markInvocationStart();
        }
        // A task body that threw before its exit would otherwise keep a pooled worker inside a
        // ForkJoinTask for the rest of the run (#940).
        if (forkJoinTaskBlockingDetector != null) {
            forkJoinTaskBlockingDetector.markInvocationStart();
        }
        // Likewise a CompletableFuture callback that threw before its exit, on a reused pool
        // thread (#941).
        if (cfBlockingCallbackDetector != null) {
            cfBlockingCallbackDetector.markInvocationStart();
        }
        // A monitor a throwing body never released, a pending VarHandle read, and a blocking
        // section that never ended each belong to the round that recorded them (#964).
        if (nestedMonitorLockoutDetector != null) {
            nestedMonitorLockoutDetector.markInvocationStart();
        }
        if (varHandleNonAtomicUpdateDetector != null) {
            varHandleNonAtomicUpdateDetector.markInvocationStart();
        }
        if (virtualThreadCarrierExhaustionDetector != null) {
            virtualThreadCarrierExhaustionDetector.markInvocationStart();
        }
    }

    /**
     * Set by {@code ConcurrencyRunner} before each invocation round.
     *
     * <p>Runner-only: public because {@code ConcurrencyRunner} is in another package, not part of
     * the test-author API. It runs on the runner's thread, which never has a run's context
     * installed; called from a test body it throws {@link IllegalStateException} (#947).
     *
     * @param seed the seed for this round, so a reported interleaving can be replayed
     */
    public void setReplaySeedForRound(long seed) {
        requireRunnerThread("setReplaySeedForRound");
        this.currentRoundSeed = seed;
    }

    // ---- Rendezvous (opened per round by ConcurrencyRunner) ----

    /** The most parties a {@link Phaser} accepts; a larger round opens no rendezvous. */
    private static final int MAX_RENDEZVOUS_PARTIES = 65_535;

    /**
     * This round's rendezvous, one party per worker, replaced at every round start. A
     * {@link Phaser} rather than a {@code CyclicBarrier}: {@link Phaser#forceTermination()} is
     * sticky, so a peer that arrives after the round broke fails at once, where a barrier's
     * {@code reset()} would let it wait out the round.
     */
    private volatile @Nullable Phaser roundRendezvous;

    /** When this round's time runs out: the bound {@link #rendezvous()} waits for by default. */
    private volatile long roundDeadlineNanos;

    /**
     * One invocation round of one run. Compared by identity, so round 1 of one run is never round
     * 1 of another; {@link OperationHistory} keys a round's operations and subject on it (#924).
     */
    static final class Round {
        private final int number;

        Round(int number) {
            this.number = number;
        }

        /** {@return the round's position in its run, from 1} */
        int number() {
            return number;
        }
    }

    /** The round in progress, replaced at every round start before its workers exist. */
    private volatile @Nullable Round currentRound;

    /** Rounds opened so far; written only by the runner thread, between rounds. */
    private int roundsOpened;

    /**
     * Called by {@code ConcurrencyRunner} before it starts a round's workers, with the number of
     * workers and the time the round has left. From a body it would replace the rendezvous the
     * round's workers are already waiting at.
     *
     * <p>Runner-only: public because {@code ConcurrencyRunner} is in another package, not part of
     * the test-author API. It runs on the runner's thread, which never has a run's context
     * installed; called from a test body it throws {@link IllegalStateException} (#947).
     *
     * @param workers        the round's worker count, which every rendezvous waits for
     * @param roundTimeoutMs the time left in the round, the default rendezvous bound
     * @since 1.13.0
     */
    public void openRendezvousForRound(int workers, long roundTimeoutMs) {
        requireRunnerThread("openRendezvousForRound");
        this.roundDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(roundTimeoutMs);
        this.roundRendezvous = workers <= MAX_RENDEZVOUS_PARTIES ? new Phaser(workers) : null;
        roundsOpened++;
        this.currentRound = new Round(roundsOpened);
    }

    /**
     * {@return the round the calling worker is in, or {@code null} outside an {@code @AsyncTest}
     * round}
     */
    static @Nullable Round currentRound() {
        AsyncTestContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.currentRound;
    }

    /**
     * Called by {@code ConcurrencyRunner} when a worker's body throws, so the peers waiting at this
     * round's rendezvous, and any that arrive later, fail at once instead of waiting out the round
     * for a worker that will never come.
     *
     * <p>Runner-facing, not part of the test-author API, but not refused from a body: the runner
     * calls it on the failing worker's own thread. A body that calls it fails every peer's
     * rendezvous in the round, exactly as a body that throws does (#947).
     *
     * @since 1.13.0
     */
    public void breakRendezvous() {
        Phaser rendezvous = roundRendezvous;
        if (rendezvous != null) {
            rendezvous.forceTermination();
        }
    }

    /**
     * Waits until every worker of the current round has called this, bounded by the time the
     * round has left.
     *
     * <p>Call it from an {@code @AsyncTest} body to make the round's workers meet at a point inside
     * the body, for example after each has prepared its state and before any acts on a peer's.
     * Every worker must call it the same number of times per body; each call is one meeting point.
     * The runner already releases the workers together at the start of the body; this is for the
     * points after that.
     *
     * <p>It fails the worker with an {@link AssertionError} when the round cannot meet: when a peer
     * threw first (the runner breaks the rendezvous so nobody waits for it), when the bound passes
     * (the message says how many workers arrived), or when the round is cancelled.
     *
     * @throws IllegalStateException outside an {@code @AsyncTest} worker, or in a round of more than
     *                               65,535 workers
     * @since 1.13.0
     */
    @API(status = Status.EXPERIMENTAL, since = "1.13.0")
    public static void rendezvous() {
        awaitRendezvous(null);
    }

    /**
     * Like {@link #rendezvous()}, but waits at most {@code timeout} instead of the time the round has
     * left: use it when the meeting should take far less than the round, so a missing worker fails
     * fast.
     *
     * @param timeout how long this worker waits for the rest of the round
     * @throws IllegalStateException outside an {@code @AsyncTest} worker, or in a round of more than
     *                               65,535 workers
     * @since 1.13.0
     */
    @API(status = Status.EXPERIMENTAL, since = "1.13.0")
    public static void rendezvous(Duration timeout) {
        awaitRendezvous(Objects.requireNonNull(timeout, "timeout"));
    }

    private static void awaitRendezvous(@Nullable Duration timeout) {
        AsyncTestContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                "rendezvous() can only be called by a worker inside an @AsyncTest method.");
        }
        Phaser rendezvous = ctx.roundRendezvous;
        if (rendezvous == null) {
            throw new IllegalStateException("No rendezvous is open: this round has more than "
                + MAX_RENDEZVOUS_PARTIES + " workers, the most a rendezvous can hold.");
        }
        long timeoutNanos = timeout != null
            ? timeout.toNanos()
            : Math.max(0L, ctx.roundDeadlineNanos - System.nanoTime());
        int phase = rendezvous.arrive();
        try {
            if (phase < 0 || rendezvous.awaitAdvanceInterruptibly(phase, timeoutNanos, TimeUnit.NANOSECONDS) < 0) {
                throw new AssertionError("The rendezvous was broken: a peer failed or gave up before every"
                    + " worker of the round reached it. The peer's own failure is reported with this one.");
            }
        } catch (TimeoutException e) {
            // Read before terminating, which is what releases the peers still waiting.
            int arrived = rendezvous.getArrivedParties();
            int workers = rendezvous.getRegisteredParties();
            rendezvous.forceTermination();
            throw new AssertionError("The rendezvous timed out after "
                + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + " ms with " + arrived + " of "
                + workers + " workers arrived: a worker returned, or is blocked,"
                + " before calling rendezvous(), or calls it fewer times than its peers.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rendezvous.forceTermination();
            throw new AssertionError("Interrupted while waiting at the rendezvous; the round was cancelled.", e);
        }
    }

    /**
     * Called by {@code ConcurrencyRunner} when a round has timed out, before it interrupts the
     * round's workers, so a detector can tell that interrupt from one the test body sent itself.
     * Touches only this context's own detector instances, never the {@code ThreadLocal}, so
     * install/uninstall symmetry is unaffected.
     *
     * <p>Runner-only: public because {@code ConcurrencyRunner} is in another package, not part of
     * the test-author API. It runs on the runner's thread, which never has a run's context
     * installed; called from a test body it throws {@link IllegalStateException} (#947).
     *
     * @since 1.12.1
     */
    public void markRoundTimedOut() {
        requireRunnerThread("markRoundTimedOut");
        if (exchangerDetector != null) {
            exchangerDetector.markRoundTimedOut();
        }
        if (cyclicBarrierDetector != null) {
            cyclicBarrierDetector.markRoundTimedOut();
        }
    }

    /**
     * Refuses a runner-only round call from a test body. The runner calls these on its own thread,
     * which never has a run's context installed; a body's thread always has one (#947).
     *
     * @param method the method refused, for the message
     */
    private static void requireRunnerThread(String method) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("AsyncTestContext." + method + "() belongs to the"
                    + " runner, which calls it between rounds; a test body cannot call it, since"
                    + " it would change the round every worker is in.");
        }
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
        stopLending();
        Map<String, String> reports = registry.analyzeAllNamed();
        appendExternalFindings(reports);
        appendRunChecks(reports);
        return reports;
    }

    /**
     * Checks the test body registered for this run, such as a verified {@link OperationHistory}
     * (#934), keyed by owner so a body that registers on every call registers once. Written by
     * workers, read by the runner thread at analysis after they have finished.
     */
    private final Map<Object, java.util.function.Supplier<List<Violation>>> runChecks =
            new ConcurrentHashMap<>();

    /**
     * Registers {@code check} for this run, once per {@code owner}; its findings join the
     * reports at analysis exactly as a third-party detector's do. The check must not throw.
     */
    void addRunCheck(Object owner, java.util.function.Supplier<List<Violation>> check) {
        runChecks.putIfAbsent(owner, check);
    }

    /** {@return the context installed on the calling worker, or {@code null} outside a run} */
    static @Nullable AsyncTestContext currentContext() {
        return CURRENT.get();
    }

    /** Merges the registered run checks' findings into {@code reports}, as external ones are. */
    private void appendRunChecks(Map<String, String> reports) {
        for (java.util.function.Supplier<List<Violation>> check : runChecks.values()) {
            for (Violation v : check.get()) {
                String line = v.severity().getLabel() + " " + v.detector() + ": " + v.message();
                reports.merge(v.detector(), line, (existing, added) -> existing + "\n" + added);
            }
        }
    }

    /**
     * {@return the per-finding grades from the most recent {@link #analyzeAllNamed()} pass}
     *
     * <p>Present only for detectors whose report implements
     * {@link se.deversity.asynctest.diagnostics.GradedFindings}. The {@code failOn} gate uses them
     * to judge a detector's findings individually rather than as one block, which is what lets a
     * verdict-grade finding fail a build even though the same detector can also produce a
     * prompt-grade one. Callers that find no entry fall back to the detector's own tier and
     * severity. Each tier is already clamped to the detector's evidence cap
     * ({@link se.deversity.asynctest.diagnostics.DetectorTrust#clampToCap}), so a grade here never
     * claims more than the detector decides from.
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
     * {@return the most severe structured severity per detector from the most recent
     * {@link #analyzeAllNamed()} pass}
     *
     * <p>Present only for detectors whose report keeps its findings as {@link Violation}s beside
     * the text. The {@code failOn} gate prefers this over the severity it can read from the text
     * ({@link se.deversity.asynctest.diagnostics.DetectorDefaultSeverity#of(String, String,
     * se.deversity.asynctest.diagnostics.IssueSeverity)}). Call after {@link #analyzeAllNamed()}.
     *
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public Map<String, se.deversity.asynctest.diagnostics.IssueSeverity> findingSeverities() {
        return registry.lastSeverities();
    }

    /**
     * {@return the messages of each report's structured findings from the most recent
     * {@link #analyzeAllNamed()} pass, keyed by detector}
     *
     * <p>Absent for a report that keeps no {@link Violation}s, which since #801 means only a
     * third-party detector. The runner's one-line summary of a folded block counts and heads with
     * these rather than with the report's bullets, which also list context and advice (#773).
     * Call after {@link #analyzeAllNamed()}.
     *
     * @since 1.12.4
     */
    @API(status = Status.EXPERIMENTAL)
    public Map<String, List<String>> findingMessages() {
        return registry.lastMessages();
    }

    /**
     * {@return the notes that are not findings from the most recent {@link #analyzeAllNamed()}
     * pass, keyed by detector}
     *
     * <p>Only reports with no finding contribute: a report with a finding carries its notes in
     * its own text. The runner logs these as {@code runner.detector.note}, because a report with
     * no finding is never printed (#816). Call after {@link #analyzeAllNamed()}.
     *
     * @since 1.12.3
     */
    @API(status = Status.INTERNAL, since = "1.12.3")
    public Map<String, List<String>> detectorNotes() {
        return registry.lastNotes();
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
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#FALSE_SHARING} is not enabled
     *
     * @return the {@link FalseSharingDetector} for the active {@code @AsyncTest} context
     */
    public static FalseSharingDetector falseSharingDetector() {
        return require(DetectorType.FALSE_SHARING, c -> c.falseSharingDetector);
    }

    /**
     * Returns the {@link WakeupDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#WAKEUP_ISSUES} is not enabled
     *
     * @return the {@link WakeupDetector} for the active {@code @AsyncTest} context
     */
    public static WakeupDetector wakeupDetector() {
        return require(DetectorType.WAKEUP_ISSUES, c -> c.wakeupDetector);
    }

    /**
     * Returns the {@link ConstructorSafetyValidator} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CONSTRUCTOR_SAFETY} is not enabled
     *
     * @return the {@link ConstructorSafetyValidator} for the active {@code @AsyncTest} context
     */
    public static ConstructorSafetyValidator constructorSafetyValidator() {
        return require(DetectorType.CONSTRUCTOR_SAFETY, c -> c.constructorSafetyValidator);
    }

    /**
     * Returns the {@link ABAProblemDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#ABA_PROBLEM} is not enabled
     *
     * @return the {@link ABAProblemDetector} for the active {@code @AsyncTest} context
     */
    public static ABAProblemDetector abaProblemDetector() {
        return require(DetectorType.ABA_PROBLEM, c -> c.abaProblemDetector);
    }

    /**
     * Returns the {@link LockOrderValidator} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LOCK_ORDER} is not enabled
     *
     * @return the {@link LockOrderValidator} for the active {@code @AsyncTest} context
     */
    public static LockOrderValidator lockOrderValidator() {
        return require(DetectorType.LOCK_ORDER, c -> c.lockOrderValidator);
    }

    /**
     * Returns the {@link SynchronizerMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SYNCHRONIZERS} is not enabled
     *
     * @return the {@link SynchronizerMonitor} for the active {@code @AsyncTest} context
     */
    public static SynchronizerMonitor synchronizerMonitor() {
        return require(DetectorType.SYNCHRONIZERS, c -> c.synchronizerMonitor);
    }

    /**
     * Returns the {@link ThreadPoolMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_POOL} is not enabled
     *
     * @return the {@link ThreadPoolMonitor} for the active {@code @AsyncTest} context
     */
    public static ThreadPoolMonitor threadPoolMonitor() {
        return require(DetectorType.THREAD_POOL, c -> c.threadPoolMonitor);
    }

    /**
     * Returns the {@link MemoryOrderingMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#MEMORY_ORDERING} is not enabled
     *
     * @return the {@link MemoryOrderingMonitor} for the active {@code @AsyncTest} context
     */
    public static MemoryOrderingMonitor memoryOrderingMonitor() {
        return require(DetectorType.MEMORY_ORDERING, c -> c.memoryOrderingMonitor);
    }

    /**
     * Returns the {@link PipelineMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#ASYNC_PIPELINE} is not enabled
     *
     * @return the {@link PipelineMonitor} for the active {@code @AsyncTest} context
     */
    public static PipelineMonitor pipelineMonitor() {
        return require(DetectorType.ASYNC_PIPELINE, c -> c.pipelineMonitor);
    }

    /**
     * Returns the {@link ReadWriteLockMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#READ_WRITE_LOCK_FAIRNESS} is not enabled
     *
     * @return the {@link ReadWriteLockMonitor} for the active {@code @AsyncTest} context
     */
    public static ReadWriteLockMonitor readWriteLockMonitor() {
        return require(DetectorType.READ_WRITE_LOCK_FAIRNESS, c -> c.readWriteLockMonitor);
    }

    /**
     * Returns the {@link SemaphoreMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SEMAPHORE} is not enabled
     *
     * @return the {@link SemaphoreMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static SemaphoreMisuseDetector semaphoreMisuseDetector() {
        return require(DetectorType.SEMAPHORE, c -> c.semaphoreMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureExceptionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COMPLETABLE_FUTURE_EXCEPTIONS} is not enabled
     *
     * @return the {@link CompletableFutureExceptionDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureExceptionDetector completableFutureExceptionDetector() {
        return require(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, c -> c.completableFutureExceptionDetector);
    }

    /**
     * Returns the {@link CompletableFutureCompletionLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COMPLETABLE_FUTURE_COMPLETION_LEAKS} is not enabled
     * @since 1.2.0
     *
     * @return the {@link CompletableFutureCompletionLeakDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCompletionLeakDetector completableFutureCompletionLeakDetector() {
        return require(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, c -> c.completableFutureCompletionLeakDetector);
    }

    /**
     * Returns the {@link VirtualThreadPinningDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VIRTUAL_THREAD_PINNING} is not enabled
     * @since 1.2.0
     *
     * @return the {@link VirtualThreadPinningDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadPinningDetector virtualThreadPinningDetector() {
        return require(DetectorType.VIRTUAL_THREAD_PINNING, c -> c.virtualThreadPinningDetector);
    }

    /**
     * Returns the {@link ThreadPoolDeadlockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_POOL_DEADLOCK} is not enabled
     * @since 1.2.0
     *
     * @return the {@link ThreadPoolDeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadPoolDeadlockDetector threadPoolDeadlockDetector() {
        return require(DetectorType.THREAD_POOL_DEADLOCK, c -> c.threadPoolDeadlockDetector);
    }

    /**
     * Returns the {@link ConcurrentModificationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CONCURRENT_MODIFICATIONS} is not enabled
     *
     * @return the {@link ConcurrentModificationDetector} for the active {@code @AsyncTest} context
     */
    public static ConcurrentModificationDetector concurrentModificationDetector() {
        return require(DetectorType.CONCURRENT_MODIFICATIONS, c -> c.concurrentModificationDetector);
    }

    /**
     * Returns the {@link LockLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LOCK_LEAKS} is not enabled
     *
     * @return the {@link LockLeakDetector} for the active {@code @AsyncTest} context
     */
    public static LockLeakDetector lockLeakDetector() {
        return require(DetectorType.LOCK_LEAKS, c -> c.lockLeakDetector);
    }

    /**
     * Returns the {@link SharedRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_RANDOM} is not enabled
     *
     * @return the {@link SharedRandomDetector} for the active {@code @AsyncTest} context
     */
    public static SharedRandomDetector sharedRandomDetector() {
        return require(DetectorType.SHARED_RANDOM, c -> c.sharedRandomDetector);
    }

    /**
     * Returns the {@link BlockingQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#BLOCKING_QUEUE} is not enabled
     *
     * @return the {@link BlockingQueueDetector} for the active {@code @AsyncTest} context
     */
    public static BlockingQueueDetector blockingQueueDetector() {
        return require(DetectorType.BLOCKING_QUEUE, c -> c.blockingQueueDetector);
    }

    /**
     * Returns the {@link ConditionVariableDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CONDITION_VARIABLES} is not enabled
     *
     * @return the {@link ConditionVariableDetector} for the active {@code @AsyncTest} context
     */
    public static ConditionVariableDetector conditionVariableDetector() {
        return require(DetectorType.CONDITION_VARIABLES, c -> c.conditionVariableDetector);
    }

    /**
     * Returns the {@link SimpleDateFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SIMPLE_DATE_FORMAT} is not enabled
     *
     * @return the {@link SimpleDateFormatDetector} for the active {@code @AsyncTest} context
     */
    public static SimpleDateFormatDetector simpleDateFormatDetector() {
        return require(DetectorType.SIMPLE_DATE_FORMAT, c -> c.simpleDateFormatDetector);
    }

    /**
     * Returns the {@link ParallelStreamDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#PARALLEL_STREAMS} is not enabled
     *
     * @return the {@link ParallelStreamDetector} for the active {@code @AsyncTest} context
     */
    public static ParallelStreamDetector parallelStreamDetector() {
        return require(DetectorType.PARALLEL_STREAMS, c -> c.parallelStreamDetector);
    }

    /**
     * Returns the {@link ResourceLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#RESOURCE_LEAKS} is not enabled
     *
     * @return the {@link ResourceLeakDetector} for the active {@code @AsyncTest} context
     */
    public static ResourceLeakDetector resourceLeakDetector() {
        return require(DetectorType.RESOURCE_LEAKS, c -> c.resourceLeakDetector);
    }

    /**
     * Returns the {@link CountDownLatchDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COUNTDOWN_LATCH} is not enabled
     *
     * @return the {@link CountDownLatchDetector} for the active {@code @AsyncTest} context
     */
    public static CountDownLatchDetector countDownLatchDetector() {
        return require(DetectorType.COUNTDOWN_LATCH, c -> c.countDownLatchDetector);
    }

    /**
     * Returns the {@link CyclicBarrierDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CYCLIC_BARRIER} is not enabled
     *
     * @return the {@link CyclicBarrierDetector} for the active {@code @AsyncTest} context
     */
    public static CyclicBarrierDetector cyclicBarrierDetector() {
        return require(DetectorType.CYCLIC_BARRIER, c -> c.cyclicBarrierDetector);
    }

    /**
     * Returns the {@link ReentrantLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#REENTRANT_LOCK} is not enabled
     *
     * @return the {@link ReentrantLockDetector} for the active {@code @AsyncTest} context
     */
    public static ReentrantLockDetector reentrantLockDetector() {
        return require(DetectorType.REENTRANT_LOCK, c -> c.reentrantLockDetector);
    }

    /**
     * Returns the {@link VolatileArrayDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VOLATILE_ARRAY} is not enabled
     *
     * @return the {@link VolatileArrayDetector} for the active {@code @AsyncTest} context
     */
    public static VolatileArrayDetector volatileArrayDetector() {
        return require(DetectorType.VOLATILE_ARRAY, c -> c.volatileArrayDetector);
    }

    /**
     * Returns the {@link DoubleCheckedLockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#DOUBLE_CHECKED_LOCKING} is not enabled
     *
     * @return the {@link DoubleCheckedLockingDetector} for the active {@code @AsyncTest} context
     */
    public static DoubleCheckedLockingDetector doubleCheckedLockingDetector() {
        return require(DetectorType.DOUBLE_CHECKED_LOCKING, c -> c.doubleCheckedLockingDetector);
    }

    /**
     * Returns the {@link WaitTimeoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#WAIT_TIMEOUT} is not enabled
     *
     * @return the {@link WaitTimeoutDetector} for the active {@code @AsyncTest} context
     */
    public static WaitTimeoutDetector waitTimeoutDetector() {
        return require(DetectorType.WAIT_TIMEOUT, c -> c.waitTimeoutDetector);
    }

    /**
     * Returns the {@link LockContentionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LOCK_CONTENTION} is not enabled
     *
     * @return the {@link LockContentionDetector} for the active {@code @AsyncTest} context
     */
    public static LockContentionDetector lockContentionDetector() {
        return require(DetectorType.LOCK_CONTENTION, c -> c.lockContentionDetector);
    }

    /**
     * Returns the {@link SynchronizedNonFinalDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SYNCHRONIZED_NON_FINAL} is not enabled
     *
     * @return the {@link SynchronizedNonFinalDetector} for the active {@code @AsyncTest} context
     */
    public static SynchronizedNonFinalDetector synchronizedNonFinalDetector() {
        return require(DetectorType.SYNCHRONIZED_NON_FINAL, c -> c.synchronizedNonFinalDetector);
    }

    /**
     * Returns the {@link MissedSignalDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#MISSED_SIGNAL} is not enabled
     *
     * @return the {@link MissedSignalDetector} for the active {@code @AsyncTest} context
     */
    public static MissedSignalDetector missedSignalDetector() {
        return require(DetectorType.MISSED_SIGNAL, c -> c.missedSignalDetector);
    }

    /**
     * Returns the {@link LazyInitRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LAZY_INIT_RACE} is not enabled
     *
     * @return the {@link LazyInitRaceDetector} for the active {@code @AsyncTest} context
     */
    public static LazyInitRaceDetector lazyInitRaceDetector() {
        return require(DetectorType.LAZY_INIT_RACE, c -> c.lazyInitRaceDetector);
    }

    /**
     * Returns the {@link PhaserDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#PHASER} is not enabled
     *
     * @return the {@link PhaserDetector} for the active {@code @AsyncTest} context
     */
    public static PhaserDetector phaserDetector() {
        return require(DetectorType.PHASER, c -> c.phaserDetector);
    }

    /**
     * Returns the {@link StampedLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#STAMPED_LOCK} is not enabled
     *
     * @return the {@link StampedLockDetector} for the active {@code @AsyncTest} context
     */
    public static StampedLockDetector stampedLockDetector() {
        return require(DetectorType.STAMPED_LOCK, c -> c.stampedLockDetector);
    }

    /**
     * Returns the {@link ExchangerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#EXCHANGER} is not enabled
     *
     * @return the {@link ExchangerDetector} for the active {@code @AsyncTest} context
     */
    public static ExchangerDetector exchangerDetector() {
        return require(DetectorType.EXCHANGER, c -> c.exchangerDetector);
    }

    /**
     * Returns the {@link ScheduledExecutorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SCHEDULED_EXECUTOR} is not enabled
     *
     * @return the {@link ScheduledExecutorDetector} for the active {@code @AsyncTest} context
     */
    public static ScheduledExecutorDetector scheduledExecutorDetector() {
        return require(DetectorType.SCHEDULED_EXECUTOR, c -> c.scheduledExecutorDetector);
    }

    /**
     * Returns the {@link ForkJoinPoolDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#FORK_JOIN_POOL} is not enabled
     *
     * @return the {@link ForkJoinPoolDetector} for the active {@code @AsyncTest} context
     */
    public static ForkJoinPoolDetector forkJoinPoolDetector() {
        return require(DetectorType.FORK_JOIN_POOL, c -> c.forkJoinPoolDetector);
    }

    /**
     * Returns the {@link ThreadFactoryDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_FACTORY} is not enabled
     *
     * @return the {@link ThreadFactoryDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadFactoryDetector threadFactoryDetector() {
        return require(DetectorType.THREAD_FACTORY, c -> c.threadFactoryDetector);
    }

    // ---- Phase 4: Infrastructure & Resource Management ----

    /**
     * Returns the {@link ThreadLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_LEAKS} is not enabled
     *
     * @return the {@link ThreadLeakDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadLeakDetector threadLeakDetector() {
        return require(DetectorType.THREAD_LEAKS, c -> c.threadLeakDetector);
    }

    /**
     * Returns the {@link SleepInLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SLEEP_IN_LOCK} is not enabled
     *
     * @return the {@link SleepInLockDetector} for the active {@code @AsyncTest} context
     */
    public static SleepInLockDetector sleepInLockDetector() {
        return require(DetectorType.SLEEP_IN_LOCK, c -> c.sleepInLockDetector);
    }

    /**
     * Returns the {@link UnboundedQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#UNBOUNDED_QUEUE} is not enabled
     *
     * @return the {@link UnboundedQueueDetector} for the active {@code @AsyncTest} context
     */
    public static UnboundedQueueDetector unboundedQueueDetector() {
        return require(DetectorType.UNBOUNDED_QUEUE, c -> c.unboundedQueueDetector);
    }

    /**
     * Returns the {@link ThreadStarvationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_STARVATION} is not enabled
     *
     * @return the {@link ThreadStarvationDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadStarvationDetector threadStarvationDetector() {
        return require(DetectorType.THREAD_STARVATION, c -> c.threadStarvationDetector);
    }

    // ---- Phase 5: Thread-Safety of Common Types ----

    /**
     * Returns the {@link CalendarDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CALENDAR} is not enabled
     *
     * @return the {@link CalendarDetector} for the active {@code @AsyncTest} context
     */
    public static CalendarDetector calendarDetector() {
        return require(DetectorType.CALENDAR, c -> c.calendarDetector);
    }

    /**
     * Returns the {@link SharedCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_COLLECTIONS} is not enabled
     *
     * @return the {@link SharedCollectionDetector} for the active {@code @AsyncTest} context
     */
    public static SharedCollectionDetector sharedCollectionDetector() {
        return require(DetectorType.SHARED_COLLECTIONS, c -> c.sharedCollectionDetector);
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
        AsyncTestContext context = agentContext();
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
     * {@return the {@link SimpleDateFormatDetector} for the calling thread's test, or
     * {@code null}}
     *
     * <p>Same null-returning contract as {@link #currentSharedCollectionDetector()}: the caller is
     * {@link AgentSharedInstanceHooks}, running inside woven code that does not know a test is in
     * progress.
     */
    static @Nullable SimpleDateFormatDetector currentSimpleDateFormatDetector() {
        AsyncTestContext context = agentContext();
        return context == null ? null : context.simpleDateFormatDetector;
    }

    /** {@return the {@link SharedMatcherDetector} for the calling thread's test, or {@code null}} */
    static @Nullable SharedMatcherDetector currentSharedMatcherDetector() {
        AsyncTestContext context = agentContext();
        return context == null ? null : context.sharedMatcherDetector;
    }

    /**
     * {@return the {@link SharedMessageDigestDetector} for the calling thread's test, or
     * {@code null}}
     */
    static @Nullable SharedMessageDigestDetector currentSharedMessageDigestDetector() {
        AsyncTestContext context = agentContext();
        return context == null ? null : context.sharedMessageDigestDetector;
    }
    /** {@return the {@link CalendarDetector} for the calling thread's test, or {@code null}} */
    static @Nullable CalendarDetector currentCalendarDetector() {
        AsyncTestContext context = agentContext();
        return context == null ? null : context.calendarDetector;
    }

    /** {@return the {@link StringBuilderDetector} for the calling thread's test, or {@code null}} */
    static @Nullable StringBuilderDetector currentStringBuilderDetector() {
        AsyncTestContext context = agentContext();
        return context == null ? null : context.stringBuilderDetector;
    }

    /** {@return the {@link SharedDecimalFormatDetector} for the calling thread's test, or {@code null}} */
    static @Nullable SharedDecimalFormatDetector currentSharedDecimalFormatDetector() {
        AsyncTestContext context = agentContext();
        return context == null ? null : context.sharedDecimalFormatDetector;
    }

    /** {@return the {@link SharedFormatterDetector} for the calling thread's test, or {@code null}} */
    static @Nullable SharedFormatterDetector currentSharedFormatterDetector() {
        AsyncTestContext context = agentContext();
        return context == null ? null : context.sharedFormatterDetector;
    }
    /** {@return the {@link SemaphoreMisuseDetector} for the calling thread's test, or {@code null}} */
    static @Nullable SemaphoreMisuseDetector currentSemaphoreMisuseDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.semaphoreMisuseDetector;
    }

    /** {@return the {@link CountDownLatchDetector} for the calling thread's test, or {@code null}} */
    static @Nullable CountDownLatchDetector currentCountDownLatchDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.countDownLatchDetector;
    }

    /** {@return the {@link LatchMisuseDetector} for the calling thread's test, or {@code null}} */
    static @Nullable LatchMisuseDetector currentLatchMisuseDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.latchMisuseDetector;
    }

    /** {@return the {@link MissedSignalDetector} for the calling thread's test, or {@code null}} */
    static @Nullable MissedSignalDetector currentMissedSignalDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.missedSignalDetector;
    }

    /** {@return the {@link BlockingQueueDetector} for the calling thread's test, or {@code null}} */
    static @Nullable BlockingQueueDetector currentBlockingQueueDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.blockingQueueDetector;
    }

    /** {@return the {@link SleepInLockDetector} for the calling thread's test, or {@code null}} */
    static @Nullable SleepInLockDetector currentSleepInLockDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.sleepInLockDetector;
    }

    /** {@return the {@link ExplicitGcDetector} for the calling thread's test, or {@code null}} */
    static @Nullable ExplicitGcDetector currentExplicitGcDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.explicitGcDetector;
    }

    /** {@return the {@link DaemonThreadHygieneDetector} for the calling thread's test, or {@code null}} */
    static @Nullable DaemonThreadHygieneDetector currentDaemonThreadHygieneDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.daemonThreadHygieneDetector;
    }

    /**
     * {@return the {@link ABAProblemDetector} for the calling thread's test, or {@code null}}
     *
     * <p>Same null-returning contract as {@link #currentSharedCollectionDetector()}: the caller is
     * {@link AgentConcurrencyUtilHooks#abaSlot}, reached from woven {@code AtomicReference} calls
     * that do not know a test is in progress (#817).
     */
    static @Nullable ABAProblemDetector currentABAProblemDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.abaProblemDetector;
    }

    /**
     * {@return the {@link SynchronizedNonFinalDetector} for the calling thread's test, or
     * {@code null}}
     *
     * <p>Same null-returning contract as {@link #currentSharedCollectionDetector()}: the caller is
     * {@link AgentMonitorHooks#monitorFieldEntered}, woven before a {@code synchronized} block that
     * does not know a test is in progress (#793).
     */
    static @Nullable SynchronizedNonFinalDetector currentSynchronizedNonFinalDetector() {
        AsyncTestContext context = CURRENT.get();
        return context == null ? null : context.synchronizedNonFinalDetector;
    }

    /**
     * Returns the {@link TimerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#TIMER} is not enabled
     *
     * @return the {@link TimerDetector} for the active {@code @AsyncTest} context
     */
    public static TimerDetector timerDetector() {
        return require(DetectorType.TIMER, c -> c.timerDetector);
    }

    /**
     * Returns the {@link CopyOnWriteCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COPY_ON_WRITE_COLLECTIONS} is not enabled
     *
     * @return the {@link CopyOnWriteCollectionDetector} for the active {@code @AsyncTest} context
     */
    public static CopyOnWriteCollectionDetector copyOnWriteCollectionDetector() {
        return require(DetectorType.COPY_ON_WRITE_COLLECTIONS, c -> c.copyOnWriteCollectionDetector);
    }

    /**
     * Returns the {@link StringBuilderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#STRING_BUILDER} is not enabled
     *
     * @return the {@link StringBuilderDetector} for the active {@code @AsyncTest} context
     */
    public static StringBuilderDetector stringBuilderDetector() {
        return require(DetectorType.STRING_BUILDER, c -> c.stringBuilderDetector);
    }

    // ---- Phase 6: Virtual Thread Concurrency (Java 21+) ----

    /**
     * Returns the {@link StructuredConcurrencyMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#STRUCTURED_CONCURRENCY} is not enabled
     *
     * @return the {@link StructuredConcurrencyMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static StructuredConcurrencyMisuseDetector structuredConcurrencyMisuseDetector() {
        return require(DetectorType.STRUCTURED_CONCURRENCY, c -> c.structuredConcurrencyMisuseDetector);
    }

    /**
     * Returns the {@link VirtualThreadContextLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VIRTUAL_THREAD_CONTEXT_LEAKS} is not enabled
     *
     * @return the {@link VirtualThreadContextLeakDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadContextLeakDetector virtualThreadContextLeakDetector() {
        return require(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, c -> c.virtualThreadContextLeakDetector);
    }

    /**
     * Returns the {@link ScopedValueMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SCOPED_VALUE} is not enabled
     *
     * @return the {@link ScopedValueMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static ScopedValueMisuseDetector scopedValueMisuseDetector() {
        return require(DetectorType.SCOPED_VALUE, c -> c.scopedValueMisuseDetector);
    }

    /**
     * Returns the {@link VirtualThreadCpuBoundTaskDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VIRTUAL_THREAD_CPU_BOUND} is not enabled
     * @since 0.7.0
     *
     * @return the {@link VirtualThreadCpuBoundTaskDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadCpuBoundTaskDetector virtualThreadCpuBoundTaskDetector() {
        return require(DetectorType.VIRTUAL_THREAD_CPU_BOUND, c -> c.virtualThreadCpuBoundTaskDetector);
    }

    /**
     * Returns the {@link VirtualThreadCarrierExhaustionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VIRTUAL_THREAD_CARRIER_EXHAUSTION} is not enabled
     * @since 0.7.0
     *
     * @return the {@link VirtualThreadCarrierExhaustionDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadCarrierExhaustionDetector virtualThreadCarrierExhaustionDetector() {
        return require(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION, c -> c.virtualThreadCarrierExhaustionDetector);
    }

    // ---- Phase 7: High-Level Concurrency Patterns ----

    /**
     * Returns the {@link HttpClientConcurrencyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#HTTP_CLIENT} is not enabled
     * @since 0.7.0
     *
     * @return the {@link HttpClientConcurrencyDetector} for the active {@code @AsyncTest} context
     */
    public static HttpClientConcurrencyDetector httpClientDetector() {
        return require(DetectorType.HTTP_CLIENT, c -> c.httpClientConcurrencyDetector);
    }

    /**
     * Returns the {@link StreamClosingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#STREAM_CLOSING} is not enabled
     * @since 0.7.0
     *
     * @return the {@link StreamClosingDetector} for the active {@code @AsyncTest} context
     */
    public static StreamClosingDetector streamClosingDetector() {
        return require(DetectorType.STREAM_CLOSING, c -> c.streamClosingDetector);
    }

    /**
     * Returns the {@link CacheConcurrencyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CACHE_CONCURRENCY} is not enabled
     * @since 0.7.0
     *
     * @return the {@link CacheConcurrencyDetector} for the active {@code @AsyncTest} context
     */
    public static CacheConcurrencyDetector cacheConcurrencyDetector() {
        return require(DetectorType.CACHE_CONCURRENCY, c -> c.cacheConcurrencyDetector);
    }

    /**
     * Returns the {@link CompletableFutureChainDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COMPLETABLEFUTURE_CHAIN} is not enabled
     * @since 0.7.0
     *
     * @return the {@link CompletableFutureChainDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureChainDetector cfChainDetector() {
        return require(DetectorType.COMPLETABLEFUTURE_CHAIN, c -> c.completableFutureChainDetector);
    }

    // ---- Phase 8: Lifecycle & Structural Correctness ----

    /**
     * Returns the {@link ExecutorShutdownDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#EXECUTOR_SHUTDOWN} is not enabled
     *
     * @return the {@link ExecutorShutdownDetector} for the active {@code @AsyncTest} context
     */
    public static ExecutorShutdownDetector executorShutdownDetector() {
        return require(DetectorType.EXECUTOR_SHUTDOWN, c -> c.executorShutdownDetector);
    }

    /**
     * Returns the {@link MutableMapKeyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#MUTABLE_MAP_KEY} is not enabled
     *
     * @return the {@link MutableMapKeyDetector} for the active {@code @AsyncTest} context
     */
    public static MutableMapKeyDetector mutableMapKeyDetector() {
        return require(DetectorType.MUTABLE_MAP_KEY, c -> c.mutableMapKeyDetector);
    }

    /**
     * Returns the {@link NestedMonitorLockoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#NESTED_MONITOR_LOCKOUT} is not enabled
     *
     * @return the {@link NestedMonitorLockoutDetector} for the active {@code @AsyncTest} context
     */
    public static NestedMonitorLockoutDetector nestedMonitorLockoutDetector() {
        return require(DetectorType.NESTED_MONITOR_LOCKOUT, c -> c.nestedMonitorLockoutDetector);
    }

    /**
     * Returns the {@link LockDowngradeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LOCK_DOWNGRADE} is not enabled
     *
     * @return the {@link LockDowngradeDetector} for the active {@code @AsyncTest} context
     */
    public static LockDowngradeDetector lockDowngradeDetector() {
        return require(DetectorType.LOCK_DOWNGRADE, c -> c.lockDowngradeDetector);
    }

    /**
     * Returns the {@link InheritableThreadLocalMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#INHERITABLE_THREAD_LOCAL} is not enabled
     *
     * @return the {@link InheritableThreadLocalMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseDetector() {
        return require(DetectorType.INHERITABLE_THREAD_LOCAL, c -> c.inheritableThreadLocalMisuseDetector);
    }

    /**
     * Returns the {@link ThreadLocalContaminationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_LOCAL_CONTAMINATION} is not enabled
     *
     * @return the {@link ThreadLocalContaminationDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadLocalContaminationDetector threadLocalContaminationDetector() {
        return require(DetectorType.THREAD_LOCAL_CONTAMINATION, c -> c.threadLocalContaminationDetector);
    }

    /**
     * Returns the {@link AtomicNonAtomicUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#ATOMIC_NON_ATOMIC_UPDATE} is not enabled
     *
     * @return the {@link AtomicNonAtomicUpdateDetector} for the active {@code @AsyncTest} context
     */
    public static AtomicNonAtomicUpdateDetector atomicNonAtomicUpdateDetector() {
        return require(DetectorType.ATOMIC_NON_ATOMIC_UPDATE, c -> c.atomicNonAtomicUpdateDetector);
    }

    /**
     * Returns the {@link SynchronizedCollectionIterationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SYNCHRONIZED_COLLECTION_ITERATION} is not enabled
     *
     * @return the {@link SynchronizedCollectionIterationDetector} for the active {@code @AsyncTest} context
     */
    public static SynchronizedCollectionIterationDetector synchronizedCollectionIterationDetector() {
        return require(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, c -> c.synchronizedCollectionIterationDetector);
    }

    /**
     * Returns the {@link SharedFormatterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_FORMATTER} is not enabled
     *
     * @return the {@link SharedFormatterDetector} for the active {@code @AsyncTest} context
     */
    public static SharedFormatterDetector sharedFormatterDetector() {
        return require(DetectorType.SHARED_FORMATTER, c -> c.sharedFormatterDetector);
    }

    /**
     * Returns the {@link ConcurrentMapComputeRecursionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CONCURRENT_MAP_COMPUTE_RECURSION} is not enabled
     *
     * @return the {@link ConcurrentMapComputeRecursionDetector} for the active {@code @AsyncTest} context
     */
    public static ConcurrentMapComputeRecursionDetector concurrentMapComputeRecursionDetector() {
        return require(DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, c -> c.concurrentMapComputeRecursionDetector);
    }

    /**
     * Returns the {@link SynchronizedOnLiteralDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SYNCHRONIZED_ON_LITERAL} is not enabled
     *
     * @return the {@link SynchronizedOnLiteralDetector} for the active {@code @AsyncTest} context
     */
    public static SynchronizedOnLiteralDetector synchronizedOnLiteralDetector() {
        return require(DetectorType.SYNCHRONIZED_ON_LITERAL, c -> c.synchronizedOnLiteralDetector);
    }

    /**
     * Returns the {@link PublicLockExposureDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#PUBLIC_LOCK_EXPOSURE} is not enabled
     *
     * @return the {@link PublicLockExposureDetector} for the active {@code @AsyncTest} context
     */
    public static PublicLockExposureDetector publicLockExposureDetector() {
        return require(DetectorType.PUBLIC_LOCK_EXPOSURE, c -> c.publicLockExposureDetector);
    }

    /**
     * Returns the {@link ForkJoinTaskBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#FORK_JOIN_TASK_BLOCKING} is not enabled
     *
     * @return the {@link ForkJoinTaskBlockingDetector} for the active {@code @AsyncTest} context
     */
    public static ForkJoinTaskBlockingDetector forkJoinTaskBlockingDetector() {
        return require(DetectorType.FORK_JOIN_TASK_BLOCKING, c -> c.forkJoinTaskBlockingDetector);
    }

    /**
     * Returns the {@link OptimisticReadValidationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#OPTIMISTIC_READ_VALIDATION} is not enabled
     *
     * @return the {@link OptimisticReadValidationDetector} for the active {@code @AsyncTest} context
     */
    public static OptimisticReadValidationDetector optimisticReadValidationDetector() {
        return require(DetectorType.OPTIMISTIC_READ_VALIDATION, c -> c.optimisticReadValidationDetector);
    }

    /**
     * Returns the {@link CompletableFutureCommonPoolBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CF_COMMON_POOL_BLOCKING} is not enabled
     *
     * @return the {@link CompletableFutureCommonPoolBlockingDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCommonPoolBlockingDetector cfCommonPoolBlockingDetector() {
        return require(DetectorType.CF_COMMON_POOL_BLOCKING, c -> c.cfCommonPoolBlockingDetector);
    }

    // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----

    /**
     * Returns the {@link SharedMatcherDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_MATCHER} is not enabled
     * @since 0.9.0
     *
     * @return the {@link SharedMatcherDetector} for the active {@code @AsyncTest} context
     */
    public static SharedMatcherDetector sharedMatcherDetector() {
        return require(DetectorType.SHARED_MATCHER, c -> c.sharedMatcherDetector);
    }

    /**
     * Returns the {@link SharedDecimalFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_DECIMAL_FORMAT} is not enabled
     * @since 0.9.0
     *
     * @return the {@link SharedDecimalFormatDetector} for the active {@code @AsyncTest} context
     */
    public static SharedDecimalFormatDetector sharedDecimalFormatDetector() {
        return require(DetectorType.SHARED_DECIMAL_FORMAT, c -> c.sharedDecimalFormatDetector);
    }

    /**
     * Returns the {@link WeakReferenceRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#WEAK_REFERENCE_RACE} is not enabled
     * @since 0.9.0
     *
     * @return the {@link WeakReferenceRaceDetector} for the active {@code @AsyncTest} context
     */
    public static WeakReferenceRaceDetector weakReferenceRaceDetector() {
        return require(DetectorType.WEAK_REFERENCE_RACE, c -> c.weakReferenceRaceDetector);
    }

    /**
     * Returns the {@link StatefulLambdaDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#STATEFUL_LAMBDA} is not enabled
     * @since 0.9.0
     *
     * @return the {@link StatefulLambdaDetector} for the active {@code @AsyncTest} context
     */
    public static StatefulLambdaDetector statefulLambdaDetector() {
        return require(DetectorType.STATEFUL_LAMBDA, c -> c.statefulLambdaDetector);
    }

    /**
     * Returns the {@link SharedMessageDigestDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_MESSAGE_DIGEST} is not enabled
     * @since 0.9.0
     *
     * @return the {@link SharedMessageDigestDetector} for the active {@code @AsyncTest} context
     */
    @AIPublicAPI
    public static SharedMessageDigestDetector sharedMessageDigestDetector() {
        return require(DetectorType.SHARED_MESSAGE_DIGEST, c -> c.sharedMessageDigestDetector);
    }

    /**
     * Returns the {@link SharedMessageDigestDetector} (as a unified Shared Cryptography Detector) for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_MESSAGE_DIGEST} is not enabled
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
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#INTERRUPT_SWALLOWING} is not enabled
     * @since 0.10.0
     *
     * @return the {@link InterruptSwallowingDetector} for the active {@code @AsyncTest} context
     */
    public static InterruptSwallowingDetector interruptSwallowingDetector() {
        return require(DetectorType.INTERRUPT_SWALLOWING, c -> c.interruptSwallowingDetector);
    }

    /**
     * Returns the {@link MdcContextLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#MDC_CONTEXT_LEAK} is not enabled
     * @since 0.10.0
     *
     * @return the {@link MdcContextLeakDetector} for the active {@code @AsyncTest} context
     */
    public static MdcContextLeakDetector mdcContextLeakDetector() {
        return require(DetectorType.MDC_CONTEXT_LEAK, c -> c.mdcContextLeakDetector);
    }

    /**
     * Returns the {@link SystemPropertyMutationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SYSTEM_PROPERTY_MUTATION} is not enabled
     * @since 0.10.0
     *
     * @return the {@link SystemPropertyMutationDetector} for the active {@code @AsyncTest} context
     */
    public static SystemPropertyMutationDetector systemPropertyMutationDetector() {
        return require(DetectorType.SYSTEM_PROPERTY_MUTATION, c -> c.systemPropertyMutationDetector);
    }

    /**
     * Returns the {@link FutureIgnoredDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#FUTURE_IGNORED} is not enabled
     * @since 0.10.0
     *
     * @return the {@link FutureIgnoredDetector} for the active {@code @AsyncTest} context
     */
    public static FutureIgnoredDetector futureIgnoredDetector() {
        return require(DetectorType.FUTURE_IGNORED, c -> c.futureIgnoredDetector);
    }

    /**
     * Returns the {@link ExplicitGcDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#EXPLICIT_GC} is not enabled
     * @since 0.10.0
     *
     * @return the {@link ExplicitGcDetector} for the active {@code @AsyncTest} context
     */
    public static ExplicitGcDetector explicitGcDetector() {
        return require(DetectorType.EXPLICIT_GC, c -> c.explicitGcDetector);
    }

    /**
     * Returns the {@link DeprecatedThreadApiDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#DEPRECATED_THREAD_API} is not enabled
     * @since 0.10.0
     *
     * @return the {@link DeprecatedThreadApiDetector} for the active {@code @AsyncTest} context
     */
    public static DeprecatedThreadApiDetector deprecatedThreadApiDetector() {
        return require(DetectorType.DEPRECATED_THREAD_API, c -> c.deprecatedThreadApiDetector);
    }

    /**
     * Returns the {@link SharedXmlParserDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_XML_PARSER} is not enabled
     * @since 0.10.0
     *
     * @return the {@link SharedXmlParserDetector} for the active {@code @AsyncTest} context
     */
    public static SharedXmlParserDetector sharedXmlParserDetector() {
        return require(DetectorType.SHARED_XML_PARSER, c -> c.sharedXmlParserDetector);
    }

    /**
     * Returns the {@link BoxedPrimitiveLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#BOXED_PRIMITIVE_LOCK} is not enabled
     * @since 0.10.0
     *
     * @return the {@link BoxedPrimitiveLockDetector} for the active {@code @AsyncTest} context
     */
    public static BoxedPrimitiveLockDetector boxedPrimitiveLockDetector() {
        return require(DetectorType.BOXED_PRIMITIVE_LOCK, c -> c.boxedPrimitiveLockDetector);
    }

    /**
     * Returns the {@link SharedTimeZoneDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_TIMEZONE} is not enabled
     * @since 0.10.0
     *
     * @return the {@link SharedTimeZoneDetector} for the active {@code @AsyncTest} context
     */
    public static SharedTimeZoneDetector sharedTimeZoneDetector() {
        return require(DetectorType.SHARED_TIMEZONE, c -> c.sharedTimeZoneDetector);
    }

    /**
     * Returns the {@link UncaughtExceptionHandlerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#UNCAUGHT_EXCEPTION_HANDLER} is not enabled
     * @since 0.10.0
     *
     * @return the {@link UncaughtExceptionHandlerDetector} for the active {@code @AsyncTest} context
     */
    public static UncaughtExceptionHandlerDetector uncaughtExceptionHandlerDetector() {
        return require(DetectorType.UNCAUGHT_EXCEPTION_HANDLER, c -> c.uncaughtExceptionHandlerDetector);
    }

    // ---- Phase 13 accessors (1.0.0+) ----

    /**
     * Returns the {@link DaemonThreadHygieneDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#DAEMON_THREAD_HYGIENE} is not enabled
     * @since 1.6.0
     *
     * @return the {@link DaemonThreadHygieneDetector} for the active {@code @AsyncTest} context
     */
    public static DaemonThreadHygieneDetector daemonThreadHygieneDetector() {
        return require(DetectorType.DAEMON_THREAD_HYGIENE, c -> c.daemonThreadHygieneDetector);
    }

    /**
     * Returns the {@link NotifyWithoutMonitorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#NOTIFY_WITHOUT_MONITOR} is not enabled
     * @since 1.6.0
     *
     * @return the {@link NotifyWithoutMonitorDetector} for the active {@code @AsyncTest} context
     */
    public static NotifyWithoutMonitorDetector notifyWithoutMonitorDetector() {
        return require(DetectorType.NOTIFY_WITHOUT_MONITOR, c -> c.notifyWithoutMonitorDetector);
    }

    /**
     * Returns the {@link SharedSecureRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_SECURE_RANDOM} is not enabled
     * @since 1.6.0
     *
     * @return the {@link SharedSecureRandomDetector} for the active {@code @AsyncTest} context
     */
    public static SharedSecureRandomDetector sharedSecureRandomDetector() {
        return require(DetectorType.SHARED_SECURE_RANDOM, c -> c.sharedSecureRandomDetector);
    }

    /**
     * Returns the {@link WeakHashMapSharedDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#WEAK_HASH_MAP_SHARED} is not enabled
     * @since 1.6.0
     *
     * @return the {@link WeakHashMapSharedDetector} for the active {@code @AsyncTest} context
     */
    public static WeakHashMapSharedDetector weakHashMapSharedDetector() {
        return require(DetectorType.WEAK_HASH_MAP_SHARED, c -> c.weakHashMapSharedDetector);
    }

    /**
     * Returns the {@link JdbcConnectionSharedDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#JDBC_CONNECTION_SHARED} is not enabled
     * @since 1.6.0
     *
     * @return the {@link JdbcConnectionSharedDetector} for the active {@code @AsyncTest} context
     */
    public static JdbcConnectionSharedDetector jdbcConnectionSharedDetector() {
        return require(DetectorType.JDBC_CONNECTION_SHARED, c -> c.jdbcConnectionSharedDetector);
    }

    /**
     * Returns the {@link SharedStatefulCryptoDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_STATEFUL_CRYPTO} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SharedStatefulCryptoDetector} for the active {@code @AsyncTest} context
     */
    public static SharedStatefulCryptoDetector sharedStatefulCryptoDetector() {
        return require(DetectorType.SHARED_STATEFUL_CRYPTO, c -> c.sharedStatefulCryptoDetector);
    }

    /**
     * Returns the {@link NonAtomicConcurrentMapUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CONCURRENT_MAP_CHECK_THEN_ACT} is not enabled
     * @since 1.7.0
     *
     * @return the {@link NonAtomicConcurrentMapUpdateDetector} for the active {@code @AsyncTest} context
     */
    public static NonAtomicConcurrentMapUpdateDetector nonAtomicConcurrentMapUpdateDetector() {
        return require(DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, c -> c.nonAtomicConcurrentMapUpdateDetector);
    }

    /**
     * Returns the {@link SharedDeflaterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_DEFLATER} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SharedDeflaterDetector} for the active {@code @AsyncTest} context
     */
    public static SharedDeflaterDetector sharedDeflaterDetector() {
        return require(DetectorType.SHARED_DEFLATER, c -> c.sharedDeflaterDetector);
    }

    /**
     * Returns the {@link ThisEscapeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THIS_ESCAPE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link ThisEscapeDetector} for the active {@code @AsyncTest} context
     */
    public static ThisEscapeDetector thisEscapeDetector() {
        return require(DetectorType.THIS_ESCAPE, c -> c.thisEscapeDetector);
    }

    /**
     * Returns the {@link ThreadLocalRandomMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_LOCAL_RANDOM_MISUSE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link ThreadLocalRandomMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadLocalRandomMisuseDetector threadLocalRandomMisuseDetector() {
        return require(DetectorType.THREAD_LOCAL_RANDOM_MISUSE, c -> c.threadLocalRandomMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureObtrudeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COMPLETABLE_FUTURE_OBTRUDE_ABUSE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link CompletableFutureObtrudeDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureObtrudeDetector completableFutureObtrudeDetector() {
        return require(DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE, c -> c.completableFutureObtrudeDetector);
    }

    /**
     * Returns the {@link SpuriousWakeupDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SPURIOUS_WAKEUP_HAZARD} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SpuriousWakeupDetector} for the active {@code @AsyncTest} context
     */
    public static SpuriousWakeupDetector spuriousWakeupHazardDetector() {
        return require(DetectorType.SPURIOUS_WAKEUP_HAZARD, c -> c.spuriousWakeupHazardDetector);
    }

    /**
     * Returns the {@link LockUpgradeDeadlockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LOCK_UPGRADE_DEADLOCK} is not enabled
     * @since 1.7.0
     *
     * @return the {@link LockUpgradeDeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static LockUpgradeDeadlockDetector lockUpgradeDeadlockDetector() {
        return require(DetectorType.LOCK_UPGRADE_DEADLOCK, c -> c.lockUpgradeDeadlockDetector);
    }

    /**
     * Returns the {@link TryLockMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#TRY_LOCK_MISUSE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link TryLockMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static TryLockMisuseDetector tryLockMisuseDetector() {
        return require(DetectorType.TRY_LOCK_MISUSE, c -> c.tryLockMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureBlockingCallbackDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COMPLETABLE_FUTURE_BLOCKING_CALLBACK} is not enabled
     * @since 1.7.0
     *
     * @return the {@link CompletableFutureBlockingCallbackDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureBlockingCallbackDetector cfBlockingCallbackDetector() {
        return require(DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK, c -> c.cfBlockingCallbackDetector);
    }

    /**
     * Returns the {@link StableValueMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#STABLE_VALUE_MISUSE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link StableValueMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static StableValueMisuseDetector stableValueMisuseDetector() {
        return require(DetectorType.STABLE_VALUE_MISUSE, c -> c.stableValueMisuseDetector);
    }

    /**
     * Returns the {@link StructuredTaskScopeMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#STRUCTURED_TASK_SCOPE_MISUSE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link StructuredTaskScopeMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static StructuredTaskScopeMisuseDetector structuredTaskScopeMisuseDetector() {
        return require(DetectorType.STRUCTURED_TASK_SCOPE_MISUSE, c -> c.structuredTaskScopeMisuseDetector);
    }

    /**
     * Returns the {@link GathererConcurrencyMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#GATHERER_CONCURRENCY_MISUSE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link GathererConcurrencyMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static GathererConcurrencyMisuseDetector gathererConcurrencyMisuseDetector() {
        return require(DetectorType.GATHERER_CONCURRENCY_MISUSE, c -> c.gathererConcurrencyMisuseDetector);
    }

    /**
     * Returns the {@link SharedByteBufferDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_BYTE_BUFFER} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SharedByteBufferDetector} for the active {@code @AsyncTest} context
     */
    public static SharedByteBufferDetector sharedByteBufferDetector() {
        return require(DetectorType.SHARED_BYTE_BUFFER, c -> c.sharedByteBufferDetector);
    }

    /**
     * Returns the {@link SharedCharsetCoderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_CHARSET_CODER} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SharedCharsetCoderDetector} for the active {@code @AsyncTest} context
     */
    public static SharedCharsetCoderDetector sharedCharsetCoderDetector() {
        return require(DetectorType.SHARED_CHARSET_CODER, c -> c.sharedCharsetCoderDetector);
    }

    /**
     * Returns the {@link SharedChecksumDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_CHECKSUM} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SharedChecksumDetector} for the active {@code @AsyncTest} context
     */
    public static SharedChecksumDetector sharedChecksumDetector() {
        return require(DetectorType.SHARED_CHECKSUM, c -> c.sharedChecksumDetector);
    }

    /**
     * Returns the {@link FileChannelPositionRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#FILE_CHANNEL_POSITION_RACE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link FileChannelPositionRaceDetector} for the active {@code @AsyncTest} context
     */
    public static FileChannelPositionRaceDetector fileChannelPositionRaceDetector() {
        return require(DetectorType.FILE_CHANNEL_POSITION_RACE, c -> c.fileChannelPositionRaceDetector);
    }

    /**
     * Returns the {@link SharedIteratorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_ITERATOR} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SharedIteratorDetector} for the active {@code @AsyncTest} context
     */
    public static SharedIteratorDetector sharedIteratorDetector() {
        return require(DetectorType.SHARED_ITERATOR, c -> c.sharedIteratorDetector);
    }

    /**
     * Returns the {@link HighContentionAtomicDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#HIGH_CONTENTION_ATOMIC} is not enabled
     * @since 1.7.0
     *
     * @return the {@link HighContentionAtomicDetector} for the active {@code @AsyncTest} context
     */
    public static HighContentionAtomicDetector highContentionAtomicDetector() {
        return require(DetectorType.HIGH_CONTENTION_ATOMIC, c -> c.highContentionAtomicDetector);
    }

    /**
     * Returns the {@link SharedJsonMapperReconfigDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_JSON_MAPPER_RECONFIG} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SharedJsonMapperReconfigDetector} for the active {@code @AsyncTest} context
     */
    public static SharedJsonMapperReconfigDetector sharedJsonMapperReconfigDetector() {
        return require(DetectorType.SHARED_JSON_MAPPER_RECONFIG, c -> c.sharedJsonMapperReconfigDetector);
    }

    /**
     * Returns the {@link LazyConstantMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LAZY_CONSTANT_MISUSE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link LazyConstantMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static LazyConstantMisuseDetector lazyConstantMisuseDetector() {
        return require(DetectorType.LAZY_CONSTANT_MISUSE, c -> c.lazyConstantMisuseDetector);
    }

    /**
     * Returns the {@link FinalFieldMutationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#FINAL_FIELD_MUTATION} is not enabled
     * @since 1.7.0
     *
     * @return the {@link FinalFieldMutationDetector} for the active {@code @AsyncTest} context
     */
    public static FinalFieldMutationDetector finalFieldMutationDetector() {
        return require(DetectorType.FINAL_FIELD_MUTATION, c -> c.finalFieldMutationDetector);
    }

    /**
     * Returns the {@link SharedKdfDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_KDF} is not enabled
     * @since 1.7.0
     *
     * @return the {@link SharedKdfDetector} for the active {@code @AsyncTest} context
     */
    public static SharedKdfDetector sharedKdfDetector() {
        return require(DetectorType.SHARED_KDF, c -> c.sharedKdfDetector);
    }

    /**
     * Returns the {@link LatchMisuseDetector} for the current test.
     *
     * <p>Register each latch with {@code registerLatch(latch, name, initialCount)} and record
     * {@code recordAwait} / {@code recordCountDown} around its use; the detector is analysed
     * with the rest at end of test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LATCH_MISUSE} is not enabled
     * @since 1.7.0
     *
     * @return the {@link LatchMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static LatchMisuseDetector latchMisuseDetector() {
        return require(DetectorType.LATCH_MISUSE, c -> c.latchMisuseDetector);
    }

    /**
     * Returns the {@link ExecutorDeadlockDetector} for the current test.
     *
     * <p>Register each executor with {@code registerExecutor(executor, name, maxThreads)} and
     * record {@code recordTaskSubmitted} / {@code recordTaskStarted} /
     * {@code recordWaitingOnSibling} / {@code recordTaskCompleted} around its tasks.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#EXECUTOR_DEADLOCK} is not enabled
     * @since 1.7.0
     *
     * @return the {@link ExecutorDeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static ExecutorDeadlockDetector executorDeadlockDetector() {
        return require(DetectorType.EXECUTOR_DEADLOCK, c -> c.executorDeadlockDetector);
    }

    /**
     * Returns the {@link FutureBlockingDetector} for the current test.
     *
     * <p>Register each executor with {@code registerExecutor(executor, name, maxThreads)} and
     * record {@code recordBlockingWait} where a task blocks on a {@code Future} from the same
     * pool.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#FUTURE_BLOCKING} is not enabled
     * @since 1.7.0
     *
     * @return the {@link FutureBlockingDetector} for the active {@code @AsyncTest} context
     */
    public static FutureBlockingDetector futureBlockingDetector() {
        return require(DetectorType.FUTURE_BLOCKING, c -> c.futureBlockingDetector);
    }

    /**
     * Returns the {@link FlowPublisherConcurrencyDetector} for the current test.
     *
     * <p>Bracket each {@code onNext} delivery with {@code recordNextStart} /
     * {@code recordNextEnd}, record demand with {@code recordRequest}, and terminal
     * signals with {@code recordComplete} / {@code recordError}.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#FLOW_PUBLISHER_CONCURRENCY} is not enabled
     * @since 1.7.1
     *
     * @return the {@link FlowPublisherConcurrencyDetector} for the active {@code @AsyncTest} context
     */
    public static FlowPublisherConcurrencyDetector flowPublisherConcurrencyDetector() {
        return require(DetectorType.FLOW_PUBLISHER_CONCURRENCY, c -> c.flowPublisherConcurrencyDetector);
    }

    /**
     * Returns the {@link ConfinedArenaThreadEscapeDetector} for the current test.
     *
     * <p>Register the arena with {@code recordArena}, each allocation with
     * {@code recordAllocation}, and every touch with {@code recordAccess}; the detector asks the
     * JVM whether the accessing thread is allowed to touch the segment.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#CONFINED_ARENA_THREAD_ESCAPE} is not enabled
     * @since 1.8.0
     *
     * @return the {@link ConfinedArenaThreadEscapeDetector} for the active {@code @AsyncTest} context
     */
    public static ConfinedArenaThreadEscapeDetector confinedArenaThreadEscapeDetector() {
        return require(DetectorType.CONFINED_ARENA_THREAD_ESCAPE, c -> c.confinedArenaThreadEscapeDetector);
    }

    /**
     * Returns the {@link SharedMemorySegmentRaceDetector} for the current test.
     *
     * <p>Record each access with its byte offset and length. Pass the optional {@code guard}
     * label naming the monitor held during the access, and overlapping accesses that agree on a
     * guard are treated as synchronized rather than reported.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_MEMORY_SEGMENT_RACE} is not enabled
     * @since 1.8.0
     *
     * @return the {@link SharedMemorySegmentRaceDetector} for the active {@code @AsyncTest} context
     */
    public static SharedMemorySegmentRaceDetector sharedMemorySegmentRaceDetector() {
        return require(DetectorType.SHARED_MEMORY_SEGMENT_RACE, c -> c.sharedMemorySegmentRaceDetector);
    }

    /**
     * Returns the {@link VarHandleNonAtomicUpdateDetector} for the current test.
     *
     * <p>Record reads with {@code recordGet}, writes with {@code recordSet}, and the CAS family
     * with {@code recordAtomicUpdate}. A get followed by a set with no atomic update between
     * them is a lost update.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VAR_HANDLE_NON_ATOMIC_UPDATE} is not enabled
     * @since 1.8.0
     *
     * @return the {@link VarHandleNonAtomicUpdateDetector} for the active {@code @AsyncTest} context
     */
    public static VarHandleNonAtomicUpdateDetector varHandleNonAtomicUpdateDetector() {
        return require(DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE, c -> c.varHandleNonAtomicUpdateDetector);
    }

    /**
     * Returns the {@link RecordMutableComponentLeakDetector} for the current test.
     *
     * <p>Call {@code recordShared} from every thread that touches the record. The first call
     * fingerprints each component, so a component whose contents change during the run is
     * reported as an observed mutation rather than a structural risk.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#RECORD_MUTABLE_COMPONENT_LEAK} is not enabled
     * @since 1.8.0
     *
     * @return the {@link RecordMutableComponentLeakDetector} for the active {@code @AsyncTest} context
     */
    public static RecordMutableComponentLeakDetector recordMutableComponentLeakDetector() {
        return require(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK, c -> c.recordMutableComponentLeakDetector);
    }

    /**
     * Returns the {@link StaticInitDeadlockDetector} for the current test.
     *
     * <p>Bracket a static initializer with {@code recordInitStart} / {@code recordInitEnd} and
     * announce each class it touches with {@code recordInitRequest}. Without any instrumentation
     * the detector still samples live threads for {@code <clinit>} frames.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#STATIC_INIT_DEADLOCK} is not enabled
     * @since 1.8.0
     *
     * @return the {@link StaticInitDeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static StaticInitDeadlockDetector staticInitDeadlockDetector() {
        return require(DetectorType.STATIC_INIT_DEADLOCK, c -> c.staticInitDeadlockDetector);
    }

    /**
     * Returns the {@link VirtualThreadPoolingDetector} for the current test.
     *
     * <p>Register executors with {@code registerExecutor} and call {@code recordTaskExecution}
     * once from inside each task; the detector flags pooled executors that manufacture virtual
     * threads and virtual threads observed running more than one task.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VIRTUAL_THREAD_POOLING} is not enabled
     * @since 1.8.0
     *
     * @return the {@link VirtualThreadPoolingDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadPoolingDetector virtualThreadPoolingDetector() {
        return require(DetectorType.VIRTUAL_THREAD_POOLING, c -> c.virtualThreadPoolingDetector);
    }

    /**
     * Returns the {@link PlatformThreadPerTaskDetector} for the current test.
     *
     * <p>Record each thread the test creates with {@code recordThreadCreated}, and register
     * executors with {@code registerExecutor}; the detector flags platform-thread churn and
     * thread-per-task executors backed by platform threads.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#PLATFORM_THREAD_PER_TASK} is not enabled
     * @since 1.8.0
     *
     * @return the {@link PlatformThreadPerTaskDetector} for the active {@code @AsyncTest} context
     */
    public static PlatformThreadPerTaskDetector platformThreadPerTaskDetector() {
        return require(DetectorType.PLATFORM_THREAD_PER_TASK, c -> c.platformThreadPerTaskDetector);
    }

    /**
     * Returns the {@link SharedSplittableRandomDetector} for the current test.
     *
     * <p>Register generators with {@code registerGenerator} and record each use with
     * {@code recordAccess}; the detector flags SplittableRandom and JEP 356 generators
     * accessed from more than one thread.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SHARED_SPLITTABLE_RANDOM} is not enabled
     * @since 1.8.0
     *
     * @return the {@link SharedSplittableRandomDetector} for the active {@code @AsyncTest} context
     */
    public static SharedSplittableRandomDetector sharedSplittableRandomDetector() {
        return require(DetectorType.SHARED_SPLITTABLE_RANDOM, c -> c.sharedSplittableRandomDetector);
    }

    /**
     * Returns the {@link CompletableFutureCompletionRaceDetector} for the current test.
     *
     * <p>Complete futures through {@code complete}/{@code completeExceptionally} on the detector,
     * or record the boolean each call returned with {@code recordCompletionAttempt}; the detector
     * reports the attempts that lost the race and had their value or exception discarded.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COMPLETABLE_FUTURE_COMPLETION_RACE} is not enabled
     * @since 1.9.5
     *
     * @return the {@link CompletableFutureCompletionRaceDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCompletionRaceDetector cfCompletionRaceDetector() {
        return require(DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE, c -> c.completableFutureCompletionRaceDetector);
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
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION} is not enabled
     * @since 1.9.5
     *
     * @return the {@link CompletableFutureCancellationPropagationDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCancellationPropagationDetector cfCancellationPropagationDetector() {
        return require(DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION, c -> c.completableFutureCancellationPropagationDetector);
    }

    /**
     * Returns the {@link CompletableFutureCombinatorMisuseDetector} for the current test.
     *
     * <p>Register combinator futures with {@code recordCombinator}, their constituents with
     * {@code recordConstituentCompleted} and each read with {@code recordAwait}; the detector
     * reports groups the code moved past before they had finished.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#COMPLETABLE_FUTURE_COMBINATOR_MISUSE} is not enabled
     * @since 1.9.5
     *
     * @return the {@link CompletableFutureCombinatorMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static CompletableFutureCombinatorMisuseDetector cfCombinatorMisuseDetector() {
        return require(DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE, c -> c.completableFutureCombinatorMisuseDetector);
    }

    /**
     * Returns the {@link LambdaLostUpdateDetector} for the current test.
     *
     * <p>Record each read-modify-write of a captured variable with {@code recordReadModifyWrite},
     * passing the value read and the value written; the detector reports the updates it can prove
     * were lost: two threads read the same value first, and no serial order of the recorded updates
     * could explain that.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LAMBDA_LOST_UPDATE} is not enabled
     * @since 1.9.5
     *
     * @return the {@link LambdaLostUpdateDetector} for the active {@code @AsyncTest} context
     */
    public static LambdaLostUpdateDetector lambdaLostUpdateDetector() {
        return require(DetectorType.LAMBDA_LOST_UPDATE, c -> c.lambdaLostUpdateDetector);
    }

    /**
     * Returns the {@link VirtualThreadResourceSaturationDetector} for the current test.
     *
     * <p>Declare the bounded resource with {@code registerResource(name, capacity)}, then bracket
     * each acquisition with {@code recordAcquireStart} and {@code recordAcquired}, or
     * {@code recordAcquireAbandoned} when the wait gives up; the detector reports a fan-out in which
     * more virtual threads waited at once than the resource can serve.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VIRTUAL_THREAD_RESOURCE_SATURATION} is not enabled
     * @since 1.9.5
     *
     * @return the {@link VirtualThreadResourceSaturationDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadResourceSaturationDetector vthreadResourceSaturationDetector() {
        return require(DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION, c -> c.virtualThreadResourceSaturationDetector);
    }

    /**
     * Returns the {@link VirtualThreadMonitorSerializationDetector} for the current test.
     *
     * <p>Call {@code recordMonitorEnter} immediately before the {@code synchronized} block and
     * {@code recordMonitorAcquired} inside it; the detector reports the peak number of virtual
     * threads queued at once, alongside the deepest queue overall and the distinct virtual waiters.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VIRTUAL_THREAD_MONITOR_SERIALIZATION} is not enabled
     * @since 1.9.5
     *
     * @return the {@link VirtualThreadMonitorSerializationDetector} for the active {@code @AsyncTest} context
     */
    public static VirtualThreadMonitorSerializationDetector vthreadMonitorSerializationDetector() {
        return require(DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION, c -> c.virtualThreadMonitorSerializationDetector);
    }

    /**
     * Returns the {@link ThreadLocalCacheDegradationDetector} for the current test.
     *
     * <p>Record the value each thread obtains with {@code recordCachedValue}; the detector counts
     * distinct instances by identity and reports a key that produced one per virtual thread
     * instead of one per pooled worker.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_LOCAL_CACHE_DEGRADATION} is not enabled
     * @since 1.9.5
     *
     * @return the {@link ThreadLocalCacheDegradationDetector} for the active {@code @AsyncTest} context
     */
    public static ThreadLocalCacheDegradationDetector threadLocalCacheDegradationDetector() {
        return require(DetectorType.THREAD_LOCAL_CACHE_DEGRADATION, c -> c.threadLocalCacheDegradationDetector);
    }

    /**
     * Returns the {@link ScopeJoinerMisuseDetector} for the current test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SCOPE_JOINER_MISUSE} is not enabled
     * @since 1.9.7
     *
     * @return the {@link ScopeJoinerMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static ScopeJoinerMisuseDetector scopeJoinerMisuseDetector() {
        return require(DetectorType.SCOPE_JOINER_MISUSE, c -> c.scopeJoinerMisuseDetector);
    }

    /**
     * Returns the {@link ScopeConfigurationMisuseDetector} for the current test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SCOPE_CONFIGURATION_MISUSE} is not enabled
     * @since 1.9.7
     *
     * @return the {@link ScopeConfigurationMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static ScopeConfigurationMisuseDetector scopeConfigurationMisuseDetector() {
        return require(DetectorType.SCOPE_CONFIGURATION_MISUSE, c -> c.scopeConfigurationMisuseDetector);
    }

    /**
     * Returns the {@link ScopeResultEscapeDetector} for the current test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#SCOPE_RESULT_ESCAPE} is not enabled
     * @since 1.9.7
     *
     * @return the {@link ScopeResultEscapeDetector} for the active {@code @AsyncTest} context
     */
    public static ScopeResultEscapeDetector scopeResultEscapeDetector() {
        return require(DetectorType.SCOPE_RESULT_ESCAPE, c -> c.scopeResultEscapeDetector);
    }

    /**
     * Returns the {@link LazyCollectionMisuseDetector} for the current test.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LAZY_COLLECTION_MISUSE} is not enabled
     * @since 1.9.7
     *
     * @return the {@link LazyCollectionMisuseDetector} for the active {@code @AsyncTest} context
     */
    public static LazyCollectionMisuseDetector lazyCollectionMisuseDetector() {
        return require(DetectorType.LAZY_COLLECTION_MISUSE, c -> c.lazyCollectionMisuseDetector);
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
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#DEADLOCKS} is not enabled
     * @since 1.7.0
     *
     * @return the {@link DeadlockDetector} for the active {@code @AsyncTest} context
     */
    public static DeadlockDetector deadlockDetector() {
        return require(DetectorType.DEADLOCKS, c -> c.registry.deadlockDetector);
    }

    /**
     * Returns the {@link VisibilityMonitor} for the current test.
     *
     * <p>Record each read or write of a field you suspect needs {@code volatile} with
     * {@code recordFieldAccess(fieldIdentifier, value)}; the monitor reports fields whose
     * observed value diverges between threads.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#VISIBILITY} is not enabled
     * @since 1.7.0
     *
     * @return the {@link VisibilityMonitor} for the active {@code @AsyncTest} context
     */
    public static VisibilityMonitor visibilityMonitor() {
        return require(DetectorType.VISIBILITY, AsyncTestContext::sharedVisibilityMonitor);
    }

    /**
     * Returns the {@link LivelockDetector} for the current test.
     *
     * <p>Call {@code captureSnapshot()} from inside a retry loop; the detector compares
     * successive snapshots and reports threads that stay runnable without progressing.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#LIVELOCKS} is not enabled
     * @since 1.7.0
     *
     * @return the {@link LivelockDetector} for the active {@code @AsyncTest} context
     */
    public static LivelockDetector livelockDetector() {
        return require(DetectorType.LIVELOCKS, AsyncTestContext::sharedLivelockDetector);
    }

    /**
     * Returns the {@link RaceConditionDetector} for the current test.
     *
     * <p>Record accesses to shared state with {@code recordFieldRead(owner, field)} and
     * {@code recordFieldWrite(owner, field)}; the detector reports unsynchronised
     * read/write pairs observed from different threads.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#RACE_CONDITIONS} is not enabled
     * @since 1.7.0
     *
     * @return the {@link RaceConditionDetector} for the active {@code @AsyncTest} context
     */
    public static RaceConditionDetector raceConditionDetector() {
        return require(DetectorType.RACE_CONDITIONS, AsyncTestContext::sharedRaceConditionDetector);
    }

    /**
     * Returns the {@link ThreadLocalMonitor} for the current test.
     *
     * <p>Bracket each {@code ThreadLocal} with {@code recordThreadLocalInit(tl, name)},
     * {@code recordThreadLocalAccess(tl)} and {@code recordThreadLocalCleanup(tl)}; the
     * monitor reports the ones never cleaned up on a thread that outlives the task.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#THREAD_LOCAL_LEAKS} is not enabled
     * @since 1.7.0
     *
     * @return the {@link ThreadLocalMonitor} for the active {@code @AsyncTest} context
     */
    public static ThreadLocalMonitor threadLocalMonitor() {
        return require(DetectorType.THREAD_LOCAL_LEAKS, AsyncTestContext::sharedThreadLocalMonitor);
    }

    /**
     * Returns the {@link BusyWaitDetector} for the current test.
     *
     * <p>Call {@code recordLoopIteration()} from a spin loop and {@code recordYield()} where
     * it parks, or report a loop wholesale with {@code reportSpinLoop(description, iterations)}.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#BUSY_WAITING} is not enabled
     * @since 1.7.0
     *
     * @return the {@link BusyWaitDetector} for the active {@code @AsyncTest} context
     */
    public static BusyWaitDetector busyWaitDetector() {
        return require(DetectorType.BUSY_WAITING, AsyncTestContext::sharedBusyWaitDetector);
    }

    /**
     * Returns the {@link InterruptMonitor} for the current test.
     *
     * <p>Record {@code recordInterruptException(e)} in the catch block and
     * {@code recordInterruptRestored()} where the flag is put back; the monitor reports
     * interrupts that were caught and swallowed.
     *
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@link DetectorType#INTERRUPT_MISHANDLING} is not enabled
     * @since 1.7.0
     *
     * @return the {@link InterruptMonitor} for the active {@code @AsyncTest} context
     */
    public static InterruptMonitor interruptMonitor() {
        return require(DetectorType.INTERRUPT_MISHANDLING, AsyncTestContext::sharedInterruptMonitor);
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
     *                               {@link DetectorType#ATOMICITY_VIOLATIONS} is not enabled
     * @since 1.7.0
     *
     * @return the {@link AtomicityValidator} for the active {@code @AsyncTest} context
     */
    public static AtomicityValidator atomicityValidator() {
        return require(DetectorType.ATOMICITY_VIOLATIONS, c -> c.atomicityValidator);
    }

    private static <T> T require(DetectorType type, Function<AsyncTestContext, T> fn) {
        AsyncTestContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                "AsyncTestContext is not active — this accessor can only be called inside an @AsyncTest method.");
        }
        T val = fn.apply(ctx);
        if (val == null) {
            throw new IllegalStateException(
                "Detector not active: add DetectorType." + type + " to @AsyncTest(includes = ...)"
                + " to enable it, or use detectAll = true to enable every detector at once.");
        }
        return val;
    }
}
