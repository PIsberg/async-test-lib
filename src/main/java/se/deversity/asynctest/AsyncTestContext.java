package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

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
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

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

    // ---- Agent-telemetry bridge target (1.7.0+) ----
    // Exposed via atomicityValidator() so se.deversity.asynctest.telemetry.TelemetryBridge
    // can route drained agent field-access events into the live per-test detector.
    final AtomicityValidator                    atomicityValidator;

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
        // Agent-telemetry bridge target
        atomicityValidator                     = registry.atomicityValidator;
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
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     */
    public VisibilityMonitor sharedVisibilityMonitor() {
        return registry.visibilityMonitor;
    }

    /**
     * Internal: registry-backed {@link LivelockDetector} for this context, or
     * {@code null} when {@code detectLivelocks = false}.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     */
    public LivelockDetector sharedLivelockDetector() {
        return registry.livelockDetector;
    }

    /**
     * Internal: registry-backed {@link RaceConditionDetector} for this context, or
     * {@code null} when {@code detectRaceConditions = false}.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     */
    public RaceConditionDetector sharedRaceConditionDetector() {
        return registry.raceConditionDetector;
    }

    /**
     * Internal: registry-backed {@link ThreadLocalMonitor} for this context, or
     * {@code null} when {@code detectThreadLocalLeaks = false}.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     */
    public ThreadLocalMonitor sharedThreadLocalMonitor() {
        return registry.threadLocalMonitor;
    }

    /**
     * Internal: registry-backed {@link BusyWaitDetector} for this context, or
     * {@code null} when {@code detectBusyWaiting = false}.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     */
    public BusyWaitDetector sharedBusyWaitDetector() {
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
     */
    public AtomicityValidator sharedAtomicityValidator() {
        return registry.atomicityValidator;
    }

    /**
     * Internal: registry-backed {@link InterruptMonitor} for this context, or
     * {@code null} when {@code detectInterruptMishandling = false}.
     *
     * <p>Public only so {@link se.deversity.asynctest.diagnostics.Phase1DetectorSet},
     * which lives in a different package, can call it; not part of the stable public API.
     */
    public InterruptMonitor sharedInterruptMonitor() {
        return registry.interruptMonitor;
    }

    // ---- Lifecycle (called by ConcurrencyRunner) ----

    /** Installs {@code ctx} into the calling thread's ThreadLocal. */
    @AICallersOnly({"se.deversity.asynctest.runner.ConcurrencyRunner"})
    public static void install(AsyncTestContext ctx) {
        CURRENT.set(ctx);
    }

    /** Removes the context from the calling thread's ThreadLocal. */
    @AIIdempotent(reason = "ThreadLocal.remove() is documented as a no-op when the thread has no value set; the install/uninstall symmetry rule (CLAUDE.md) tolerates extra uninstalls. ConcurrencyRunner relies on this in its outermost-finally cleanup.")
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
     */
    public static long replaySeed() {
        AsyncTestContext ctx = CURRENT.get();
        return ctx == null ? 0L : ctx.currentRoundSeed;
    }

    /** Internal: set by {@code ConcurrencyRunner} before each invocation round. */
    public void setReplaySeedForRound(long seed) {
        this.currentRoundSeed = seed;
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
     * @deprecated use {@link #semaphoreMisuseDetector()}
     */
    @Deprecated
    public static SemaphoreMisuseDetector semaphoreMonitor() {
        return require("monitorSemaphore", c -> c.semaphoreMisuseDetector);
    }

    /**
     * Returns the {@link SemaphoreMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorSemaphore = false}
     */
    public static SemaphoreMisuseDetector semaphoreMisuseDetector() {
        return semaphoreMonitor();
    }

    /**
     * Returns the {@link CompletableFutureExceptionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureExceptions = false}
     * @deprecated use {@link #completableFutureExceptionDetector()}
     */
    @Deprecated
    public static CompletableFutureExceptionDetector completableFutureMonitor() {
        return require("detectCompletableFutureExceptions", c -> c.completableFutureExceptionDetector);
    }

    /**
     * Returns the {@link CompletableFutureExceptionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureExceptions = false}
     */
    public static CompletableFutureExceptionDetector completableFutureExceptionDetector() {
        return completableFutureMonitor();
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
     * @deprecated use {@link #concurrentModificationDetector()}
     */
    @Deprecated
    public static ConcurrentModificationDetector concurrentModificationMonitor() {
        return require("detectConcurrentModifications", c -> c.concurrentModificationDetector);
    }

    /**
     * Returns the {@link ConcurrentModificationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentModifications = false}
     */
    public static ConcurrentModificationDetector concurrentModificationDetector() {
        return concurrentModificationMonitor();
    }

    /**
     * Returns the {@link LockLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockLeaks = false}
     * @deprecated use {@link #lockLeakDetector()}
     */
    @Deprecated
    public static LockLeakDetector lockLeakMonitor() {
        return require("detectLockLeaks", c -> c.lockLeakDetector);
    }

    /**
     * Returns the {@link LockLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockLeaks = false}
     */
    public static LockLeakDetector lockLeakDetector() {
        return lockLeakMonitor();
    }

    /**
     * Returns the {@link SharedRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedRandom = false}
     * @deprecated use {@link #sharedRandomDetector()}
     */
    @Deprecated
    public static SharedRandomDetector sharedRandomMonitor() {
        return require("detectSharedRandom", c -> c.sharedRandomDetector);
    }

    /**
     * Returns the {@link SharedRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedRandom = false}
     */
    public static SharedRandomDetector sharedRandomDetector() {
        return sharedRandomMonitor();
    }

    /**
     * Returns the {@link BlockingQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBlockingQueueIssues = false}
     * @deprecated use {@link #blockingQueueDetector()}
     */
    @Deprecated
    public static BlockingQueueDetector blockingQueueMonitor() {
        return require("detectBlockingQueueIssues", c -> c.blockingQueueDetector);
    }

    /**
     * Returns the {@link BlockingQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBlockingQueueIssues = false}
     */
    public static BlockingQueueDetector blockingQueueDetector() {
        return blockingQueueMonitor();
    }

    /**
     * Returns the {@link ConditionVariableDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConditionVariableIssues = false}
     * @deprecated use {@link #conditionVariableDetector()}
     */
    @Deprecated
    public static ConditionVariableDetector conditionMonitor() {
        return require("detectConditionVariableIssues", c -> c.conditionVariableDetector);
    }

    /**
     * Returns the {@link ConditionVariableDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConditionVariableIssues = false}
     */
    public static ConditionVariableDetector conditionVariableDetector() {
        return conditionMonitor();
    }

    /**
     * Returns the {@link SimpleDateFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSimpleDateFormatIssues = false}
     * @deprecated use {@link #simpleDateFormatDetector()}
     */
    @Deprecated
    public static SimpleDateFormatDetector simpleDateFormatMonitor() {
        return require("detectSimpleDateFormatIssues", c -> c.simpleDateFormatDetector);
    }

    /**
     * Returns the {@link SimpleDateFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSimpleDateFormatIssues = false}
     */
    public static SimpleDateFormatDetector simpleDateFormatDetector() {
        return simpleDateFormatMonitor();
    }

    /**
     * Returns the {@link ParallelStreamDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectParallelStreamIssues = false}
     * @deprecated use {@link #parallelStreamDetector()}
     */
    @Deprecated
    public static ParallelStreamDetector parallelStreamMonitor() {
        return require("detectParallelStreamIssues", c -> c.parallelStreamDetector);
    }

    /**
     * Returns the {@link ParallelStreamDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectParallelStreamIssues = false}
     */
    public static ParallelStreamDetector parallelStreamDetector() {
        return parallelStreamMonitor();
    }

    /**
     * Returns the {@link ResourceLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectResourceLeaks = false}
     * @deprecated use {@link #resourceLeakDetector()}
     */
    @Deprecated
    public static ResourceLeakDetector resourceLeakMonitor() {
        return require("detectResourceLeaks", c -> c.resourceLeakDetector);
    }

    /**
     * Returns the {@link ResourceLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectResourceLeaks = false}
     */
    public static ResourceLeakDetector resourceLeakDetector() {
        return resourceLeakMonitor();
    }

    /**
     * Returns the {@link CountDownLatchDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCountDownLatchIssues = false}
     * @deprecated use {@link #countDownLatchDetector()}
     */
    @Deprecated
    public static CountDownLatchDetector countDownLatchMonitor() {
        return require("detectCountDownLatchIssues", c -> c.countDownLatchDetector);
    }

    /**
     * Returns the {@link CountDownLatchDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCountDownLatchIssues = false}
     */
    public static CountDownLatchDetector countDownLatchDetector() {
        return countDownLatchMonitor();
    }

    /**
     * Returns the {@link CyclicBarrierDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCyclicBarrierIssues = false}
     * @deprecated use {@link #cyclicBarrierDetector()}
     */
    @Deprecated
    public static CyclicBarrierDetector cyclicBarrierMonitor() {
        return require("detectCyclicBarrierIssues", c -> c.cyclicBarrierDetector);
    }

    /**
     * Returns the {@link CyclicBarrierDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCyclicBarrierIssues = false}
     */
    public static CyclicBarrierDetector cyclicBarrierDetector() {
        return cyclicBarrierMonitor();
    }

    /**
     * Returns the {@link ReentrantLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectReentrantLockIssues = false}
     * @deprecated use {@link #reentrantLockDetector()}
     */
    @Deprecated
    public static ReentrantLockDetector reentrantLockMonitor() {
        return require("detectReentrantLockIssues", c -> c.reentrantLockDetector);
    }

    /**
     * Returns the {@link ReentrantLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectReentrantLockIssues = false}
     */
    public static ReentrantLockDetector reentrantLockDetector() {
        return reentrantLockMonitor();
    }

    /**
     * Returns the {@link VolatileArrayDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVolatileArrayIssues = false}
     * @deprecated use {@link #volatileArrayDetector()}
     */
    @Deprecated
    public static VolatileArrayDetector volatileArrayMonitor() {
        return require("detectVolatileArrayIssues", c -> c.volatileArrayDetector);
    }

    /**
     * Returns the {@link VolatileArrayDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVolatileArrayIssues = false}
     */
    public static VolatileArrayDetector volatileArrayDetector() {
        return volatileArrayMonitor();
    }

    /**
     * Returns the {@link DoubleCheckedLockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDoubleCheckedLocking = false}
     * @deprecated use {@link #doubleCheckedLockingDetector()}
     */
    @Deprecated
    public static DoubleCheckedLockingDetector doubleCheckedLockingMonitor() {
        return require("detectDoubleCheckedLocking", c -> c.doubleCheckedLockingDetector);
    }

    /**
     * Returns the {@link DoubleCheckedLockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDoubleCheckedLocking = false}
     */
    public static DoubleCheckedLockingDetector doubleCheckedLockingDetector() {
        return doubleCheckedLockingMonitor();
    }

    /**
     * Returns the {@link WaitTimeoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWaitTimeout = false}
     * @deprecated use {@link #waitTimeoutDetector()}
     */
    @Deprecated
    public static WaitTimeoutDetector waitTimeoutMonitor() {
        return require("detectWaitTimeout", c -> c.waitTimeoutDetector);
    }

    /**
     * Returns the {@link WaitTimeoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWaitTimeout = false}
     */
    public static WaitTimeoutDetector waitTimeoutDetector() {
        return waitTimeoutMonitor();
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
     * @deprecated use {@link #phaserDetector()}
     */
    @Deprecated
    public static PhaserDetector phaserMonitor() {
        return require("detectPhaserIssues", c -> c.phaserDetector);
    }

    /**
     * Returns the {@link PhaserDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPhaserIssues = false}
     */
    public static PhaserDetector phaserDetector() {
        return phaserMonitor();
    }

    /**
     * Returns the {@link StampedLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStampedLockIssues = false}
     * @deprecated use {@link #stampedLockDetector()}
     */
    @Deprecated
    public static StampedLockDetector stampedLockMonitor() {
        return require("detectStampedLockIssues", c -> c.stampedLockDetector);
    }

    /**
     * Returns the {@link StampedLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStampedLockIssues = false}
     */
    public static StampedLockDetector stampedLockDetector() {
        return stampedLockMonitor();
    }

    /**
     * Returns the {@link ExchangerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExchangerIssues = false}
     * @deprecated use {@link #exchangerDetector()}
     */
    @Deprecated
    public static ExchangerDetector exchangerMonitor() {
        return require("detectExchangerIssues", c -> c.exchangerDetector);
    }

    /**
     * Returns the {@link ExchangerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExchangerIssues = false}
     */
    public static ExchangerDetector exchangerDetector() {
        return exchangerMonitor();
    }

    /**
     * Returns the {@link ScheduledExecutorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScheduledExecutorIssues = false}
     * @deprecated use {@link #scheduledExecutorDetector()}
     */
    @Deprecated
    public static ScheduledExecutorDetector scheduledExecutorMonitor() {
        return require("detectScheduledExecutorIssues", c -> c.scheduledExecutorDetector);
    }

    /**
     * Returns the {@link ScheduledExecutorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectScheduledExecutorIssues = false}
     */
    public static ScheduledExecutorDetector scheduledExecutorDetector() {
        return scheduledExecutorMonitor();
    }

    /**
     * Returns the {@link ForkJoinPoolDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinPoolIssues = false}
     * @deprecated use {@link #forkJoinPoolDetector()}
     */
    @Deprecated
    public static ForkJoinPoolDetector forkJoinPoolMonitor() {
        return require("detectForkJoinPoolIssues", c -> c.forkJoinPoolDetector);
    }

    /**
     * Returns the {@link ForkJoinPoolDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinPoolIssues = false}
     */
    public static ForkJoinPoolDetector forkJoinPoolDetector() {
        return forkJoinPoolMonitor();
    }

    /**
     * Returns the {@link ThreadFactoryDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadFactoryIssues = false}
     * @deprecated use {@link #threadFactoryDetector()}
     */
    @Deprecated
    public static ThreadFactoryDetector threadFactoryMonitor() {
        return require("detectThreadFactoryIssues", c -> c.threadFactoryDetector);
    }

    /**
     * Returns the {@link ThreadFactoryDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadFactoryIssues = false}
     */
    public static ThreadFactoryDetector threadFactoryDetector() {
        return threadFactoryMonitor();
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
     * @deprecated use {@link #calendarDetector()}
     */
    @Deprecated
    public static CalendarDetector calendarMonitor() {
        return require("detectCalendarIssues", c -> c.calendarDetector);
    }

    /**
     * Returns the {@link CalendarDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCalendarIssues = false}
     */
    public static CalendarDetector calendarDetector() {
        return calendarMonitor();
    }

    /**
     * Returns the {@link SharedCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedCollections = false}
     * @deprecated use {@link #sharedCollectionDetector()}
     */
    @Deprecated
    public static SharedCollectionDetector sharedCollectionMonitor() {
        return require("detectSharedCollections", c -> c.sharedCollectionDetector);
    }

    /**
     * Returns the {@link SharedCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedCollections = false}
     */
    public static SharedCollectionDetector sharedCollectionDetector() {
        return sharedCollectionMonitor();
    }

    /**
     * Returns the {@link TimerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectTimerIssues = false}
     * @deprecated use {@link #timerDetector()}
     */
    @Deprecated
    public static TimerDetector timerMonitor() {
        return require("detectTimerIssues", c -> c.timerDetector);
    }

    /**
     * Returns the {@link TimerDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectTimerIssues = false}
     */
    public static TimerDetector timerDetector() {
        return timerMonitor();
    }

    /**
     * Returns the {@link CopyOnWriteCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCopyOnWriteCollectionIssues = false}
     * @deprecated use {@link #copyOnWriteCollectionDetector()}
     */
    @Deprecated
    public static CopyOnWriteCollectionDetector copyOnWriteMonitor() {
        return require("detectCopyOnWriteCollectionIssues", c -> c.copyOnWriteCollectionDetector);
    }

    /**
     * Returns the {@link CopyOnWriteCollectionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCopyOnWriteCollectionIssues = false}
     */
    public static CopyOnWriteCollectionDetector copyOnWriteCollectionDetector() {
        return copyOnWriteMonitor();
    }

    /**
     * Returns the {@link StringBuilderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStringBuilderIssues = false}
     * @deprecated use {@link #stringBuilderDetector()}
     */
    @Deprecated
    public static StringBuilderDetector stringBuilderMonitor() {
        return require("detectStringBuilderIssues", c -> c.stringBuilderDetector);
    }

    /**
     * Returns the {@link StringBuilderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStringBuilderIssues = false}
     */
    public static StringBuilderDetector stringBuilderDetector() {
        return stringBuilderMonitor();
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
     * @deprecated use {@link #executorShutdownDetector()}
     */
    @Deprecated
    public static ExecutorShutdownDetector executorShutdownMonitor() {
        return require("detectExecutorShutdown", c -> c.executorShutdownDetector);
    }

    /**
     * Returns the {@link ExecutorShutdownDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectExecutorShutdown = false}
     */
    public static ExecutorShutdownDetector executorShutdownDetector() {
        return executorShutdownMonitor();
    }

    /**
     * Returns the {@link MutableMapKeyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMutableMapKeys = false}
     * @deprecated use {@link #mutableMapKeyDetector()}
     */
    @Deprecated
    public static MutableMapKeyDetector mutableMapKeyMonitor() {
        return require("detectMutableMapKeys", c -> c.mutableMapKeyDetector);
    }

    /**
     * Returns the {@link MutableMapKeyDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMutableMapKeys = false}
     */
    public static MutableMapKeyDetector mutableMapKeyDetector() {
        return mutableMapKeyMonitor();
    }

    /**
     * Returns the {@link NestedMonitorLockoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectNestedMonitorLockout = false}
     * @deprecated use {@link #nestedMonitorLockoutDetector()}
     */
    @Deprecated
    public static NestedMonitorLockoutDetector nestedMonitorLockoutMonitor() {
        return require("detectNestedMonitorLockout", c -> c.nestedMonitorLockoutDetector);
    }

    /**
     * Returns the {@link NestedMonitorLockoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectNestedMonitorLockout = false}
     */
    public static NestedMonitorLockoutDetector nestedMonitorLockoutDetector() {
        return nestedMonitorLockoutMonitor();
    }

    /**
     * Returns the {@link LockDowngradeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockDowngrade = false}
     * @deprecated use {@link #lockDowngradeDetector()}
     */
    @Deprecated
    public static LockDowngradeDetector lockDowngradeMonitor() {
        return require("detectLockDowngrade", c -> c.lockDowngradeDetector);
    }

    /**
     * Returns the {@link LockDowngradeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockDowngrade = false}
     */
    public static LockDowngradeDetector lockDowngradeDetector() {
        return lockDowngradeMonitor();
    }

    /**
     * Returns the {@link InheritableThreadLocalMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectInheritableThreadLocalMisuse = false}
     * @deprecated use {@link #inheritableThreadLocalMisuseDetector()}
     */
    @Deprecated
    public static InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseMonitor() {
        return require("detectInheritableThreadLocalMisuse", c -> c.inheritableThreadLocalMisuseDetector);
    }

    /**
     * Returns the {@link InheritableThreadLocalMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectInheritableThreadLocalMisuse = false}
     */
    public static InheritableThreadLocalMisuseDetector inheritableThreadLocalMisuseDetector() {
        return inheritableThreadLocalMisuseMonitor();
    }

    /**
     * Returns the {@link UncommittedChangesDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectUncommittedChanges = false}
     * @deprecated use {@link #uncommittedChangesDetector()}
     */
    @Deprecated
    public static UncommittedChangesDetector uncommittedChangesMonitor() {
        return require("detectUncommittedChanges", c -> c.uncommittedChangesDetector);
    }

    /**
     * Returns the {@link UncommittedChangesDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectUncommittedChanges = false}
     */
    public static UncommittedChangesDetector uncommittedChangesDetector() {
        return uncommittedChangesMonitor();
    }

    /**
     * Returns the {@link ThreadLocalContaminationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalContamination = false}
     * @deprecated use {@link #threadLocalContaminationDetector()}
     */
    @Deprecated
    public static ThreadLocalContaminationDetector threadLocalContaminationMonitor() {
        return require("detectThreadLocalContamination", c -> c.threadLocalContaminationDetector);
    }

    /**
     * Returns the {@link ThreadLocalContaminationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalContamination = false}
     */
    public static ThreadLocalContaminationDetector threadLocalContaminationDetector() {
        return threadLocalContaminationMonitor();
    }

    /**
     * Returns the {@link AtomicNonAtomicUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectAtomicNonAtomicUpdates = false}
     * @deprecated use {@link #atomicNonAtomicUpdateDetector()}
     */
    @Deprecated
    public static AtomicNonAtomicUpdateDetector atomicNonAtomicUpdateMonitor() {
        return require("detectAtomicNonAtomicUpdates", c -> c.atomicNonAtomicUpdateDetector);
    }

    /**
     * Returns the {@link AtomicNonAtomicUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectAtomicNonAtomicUpdates = false}
     */
    public static AtomicNonAtomicUpdateDetector atomicNonAtomicUpdateDetector() {
        return atomicNonAtomicUpdateMonitor();
    }

    /**
     * Returns the {@link SynchronizedCollectionIterationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedCollectionIteration = false}
     * @deprecated use {@link #synchronizedCollectionIterationDetector()}
     */
    @Deprecated
    public static SynchronizedCollectionIterationDetector synchronizedCollectionIterationMonitor() {
        return require("detectSynchronizedCollectionIteration", c -> c.synchronizedCollectionIterationDetector);
    }

    /**
     * Returns the {@link SynchronizedCollectionIterationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedCollectionIteration = false}
     */
    public static SynchronizedCollectionIterationDetector synchronizedCollectionIterationDetector() {
        return synchronizedCollectionIterationMonitor();
    }

    /**
     * Returns the {@link SharedFormatterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedFormatter = false}
     * @deprecated use {@link #sharedFormatterDetector()}
     */
    @Deprecated
    public static SharedFormatterDetector sharedFormatterMonitor() {
        return require("detectSharedFormatter", c -> c.sharedFormatterDetector);
    }

    /**
     * Returns the {@link SharedFormatterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedFormatter = false}
     */
    public static SharedFormatterDetector sharedFormatterDetector() {
        return sharedFormatterMonitor();
    }

    /**
     * Returns the {@link ConcurrentMapComputeRecursionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentMapComputeRecursion = false}
     * @deprecated use {@link #concurrentMapComputeRecursionDetector()}
     */
    @Deprecated
    public static ConcurrentMapComputeRecursionDetector concurrentMapComputeRecursionMonitor() {
        return require("detectConcurrentMapComputeRecursion", c -> c.concurrentMapComputeRecursionDetector);
    }

    /**
     * Returns the {@link ConcurrentMapComputeRecursionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentMapComputeRecursion = false}
     */
    public static ConcurrentMapComputeRecursionDetector concurrentMapComputeRecursionDetector() {
        return concurrentMapComputeRecursionMonitor();
    }

    /**
     * Returns the {@link SynchronizedOnLiteralDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedOnLiteral = false}
     * @deprecated use {@link #synchronizedOnLiteralDetector()}
     */
    @Deprecated
    public static SynchronizedOnLiteralDetector synchronizedOnLiteralMonitor() {
        return require("detectSynchronizedOnLiteral", c -> c.synchronizedOnLiteralDetector);
    }

    /**
     * Returns the {@link SynchronizedOnLiteralDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSynchronizedOnLiteral = false}
     */
    public static SynchronizedOnLiteralDetector synchronizedOnLiteralDetector() {
        return synchronizedOnLiteralMonitor();
    }

    /**
     * Returns the {@link PublicLockExposureDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPublicLockExposure = false}
     * @deprecated use {@link #publicLockExposureDetector()}
     */
    @Deprecated
    public static PublicLockExposureDetector publicLockExposureMonitor() {
        return require("detectPublicLockExposure", c -> c.publicLockExposureDetector);
    }

    /**
     * Returns the {@link PublicLockExposureDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectPublicLockExposure = false}
     */
    public static PublicLockExposureDetector publicLockExposureDetector() {
        return publicLockExposureMonitor();
    }

    /**
     * Returns the {@link ForkJoinTaskBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinTaskBlocking = false}
     * @deprecated use {@link #forkJoinTaskBlockingDetector()}
     */
    @Deprecated
    public static ForkJoinTaskBlockingDetector forkJoinTaskBlockingMonitor() {
        return require("detectForkJoinTaskBlocking", c -> c.forkJoinTaskBlockingDetector);
    }

    /**
     * Returns the {@link ForkJoinTaskBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectForkJoinTaskBlocking = false}
     */
    public static ForkJoinTaskBlockingDetector forkJoinTaskBlockingDetector() {
        return forkJoinTaskBlockingMonitor();
    }

    /**
     * Returns the {@link OptimisticReadValidationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectOptimisticReadValidation = false}
     * @deprecated use {@link #optimisticReadValidationDetector()}
     */
    @Deprecated
    public static OptimisticReadValidationDetector optimisticReadValidationMonitor() {
        return require("detectOptimisticReadValidation", c -> c.optimisticReadValidationDetector);
    }

    /**
     * Returns the {@link OptimisticReadValidationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectOptimisticReadValidation = false}
     */
    public static OptimisticReadValidationDetector optimisticReadValidationDetector() {
        return optimisticReadValidationMonitor();
    }

    /**
     * Returns the {@link CompletableFutureCommonPoolBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCFCommonPoolBlocking = false}
     * @deprecated use {@link #cfCommonPoolBlockingDetector()}
     */
    @Deprecated
    public static CompletableFutureCommonPoolBlockingDetector cfCommonPoolBlockingMonitor() {
        return require("detectCFCommonPoolBlocking", c -> c.cfCommonPoolBlockingDetector);
    }

    /**
     * Returns the {@link CompletableFutureCommonPoolBlockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCFCommonPoolBlocking = false}
     */
    public static CompletableFutureCommonPoolBlockingDetector cfCommonPoolBlockingDetector() {
        return cfCommonPoolBlockingMonitor();
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
    @AIPublicAPI
    public static SharedMessageDigestDetector sharedMessageDigestDetector() {
        return require("detectSharedMessageDigest", c -> c.sharedMessageDigestDetector);
    }

    /**
     * Returns the {@link SharedMessageDigestDetector} (as a unified Shared Cryptography Detector) for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedMessageDigest = false}
     * @since 0.9.5
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

    // ---- Phase 13 accessors (1.0.0+) ----

    /**
     * Returns the {@link DaemonThreadHygieneDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDaemonThreadHygiene = false}
     * @since 1.6.0
     */
    public static DaemonThreadHygieneDetector daemonThreadHygieneDetector() {
        return require("detectDaemonThreadHygiene", c -> c.daemonThreadHygieneDetector);
    }

    /**
     * Returns the {@link NotifyWithoutMonitorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectNotifyWithoutMonitor = false}
     * @since 1.6.0
     */
    public static NotifyWithoutMonitorDetector notifyWithoutMonitorDetector() {
        return require("detectNotifyWithoutMonitor", c -> c.notifyWithoutMonitorDetector);
    }

    /**
     * Returns the {@link SharedSecureRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedSecureRandom = false}
     * @since 1.6.0
     */
    public static SharedSecureRandomDetector sharedSecureRandomDetector() {
        return require("detectSharedSecureRandom", c -> c.sharedSecureRandomDetector);
    }

    /**
     * Returns the {@link WeakHashMapSharedDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWeakHashMapShared = false}
     * @since 1.6.0
     */
    public static WeakHashMapSharedDetector weakHashMapSharedDetector() {
        return require("detectWeakHashMapShared", c -> c.weakHashMapSharedDetector);
    }

    /**
     * Returns the {@link JdbcConnectionSharedDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectJdbcConnectionShared = false}
     * @since 1.6.0
     */
    public static JdbcConnectionSharedDetector jdbcConnectionSharedDetector() {
        return require("detectJdbcConnectionShared", c -> c.jdbcConnectionSharedDetector);
    }

    /**
     * Returns the {@link SharedStatefulCryptoDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedStatefulCrypto = false}
     * @since 1.7.0
     */
    public static SharedStatefulCryptoDetector sharedStatefulCryptoDetector() {
        return require("detectSharedStatefulCrypto", c -> c.sharedStatefulCryptoDetector);
    }

    /**
     * Returns the {@link NonAtomicConcurrentMapUpdateDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentMapCheckThenAct = false}
     * @since 1.7.0
     */
    public static NonAtomicConcurrentMapUpdateDetector nonAtomicConcurrentMapUpdateDetector() {
        return require("detectConcurrentMapCheckThenAct", c -> c.nonAtomicConcurrentMapUpdateDetector);
    }

    /**
     * Returns the {@link SharedDeflaterDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedDeflater = false}
     * @since 1.7.0
     */
    public static SharedDeflaterDetector sharedDeflaterDetector() {
        return require("detectSharedDeflater", c -> c.sharedDeflaterDetector);
    }

    /**
     * Returns the {@link ThisEscapeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThisEscape = false}
     * @since 1.7.0
     */
    public static ThisEscapeDetector thisEscapeDetector() {
        return require("detectThisEscape", c -> c.thisEscapeDetector);
    }

    /**
     * Returns the {@link ThreadLocalRandomMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectThreadLocalRandomMisuse = false}
     * @since 1.7.0
     */
    public static ThreadLocalRandomMisuseDetector threadLocalRandomMisuseDetector() {
        return require("detectThreadLocalRandomMisuse", c -> c.threadLocalRandomMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureObtrudeDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureObtrudeAbuse = false}
     * @since 1.8.0
     */
    public static CompletableFutureObtrudeDetector completableFutureObtrudeDetector() {
        return require("detectCompletableFutureObtrudeAbuse", c -> c.completableFutureObtrudeDetector);
    }

    /**
     * Returns the {@link SpuriousWakeupDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSpuriousWakeupHazard = false}
     * @since 1.8.0
     */
    public static SpuriousWakeupDetector spuriousWakeupHazardDetector() {
        return require("detectSpuriousWakeupHazard", c -> c.spuriousWakeupHazardDetector);
    }

    /**
     * Returns the {@link LockUpgradeDeadlockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockUpgradeDeadlock = false}
     * @since 1.8.0
     */
    public static LockUpgradeDeadlockDetector lockUpgradeDeadlockDetector() {
        return require("detectLockUpgradeDeadlock", c -> c.lockUpgradeDeadlockDetector);
    }

    /**
     * Returns the {@link TryLockMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectTryLockMisuse = false}
     * @since 1.8.0
     */
    public static TryLockMisuseDetector tryLockMisuseDetector() {
        return require("detectTryLockMisuse", c -> c.tryLockMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureBlockingCallbackDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCFBlockingCallback = false}
     * @since 1.8.0
     */
    public static CompletableFutureBlockingCallbackDetector cfBlockingCallbackDetector() {
        return require("detectCFBlockingCallback", c -> c.cfBlockingCallbackDetector);
    }

    /**
     * Returns the {@link StableValueMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStableValueMisuse = false}
     * @since 1.7.0
     */
    public static StableValueMisuseDetector stableValueMisuseDetector() {
        return require("detectStableValueMisuse", c -> c.stableValueMisuseDetector);
    }

    /**
     * Returns the {@link StructuredTaskScopeMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectStructuredTaskScopeMisuse = false}
     * @since 1.7.0
     */
    public static StructuredTaskScopeMisuseDetector structuredTaskScopeMisuseDetector() {
        return require("detectStructuredTaskScopeMisuse", c -> c.structuredTaskScopeMisuseDetector);
    }

    /**
     * Returns the {@link GathererConcurrencyMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectGathererConcurrencyMisuse = false}
     * @since 1.7.0
     */
    public static GathererConcurrencyMisuseDetector gathererConcurrencyMisuseDetector() {
        return require("detectGathererConcurrencyMisuse", c -> c.gathererConcurrencyMisuseDetector);
    }

    /**
     * Returns the {@link SharedByteBufferDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedByteBuffer = false}
     * @since 1.7.0
     */
    public static SharedByteBufferDetector sharedByteBufferDetector() {
        return require("detectSharedByteBuffer", c -> c.sharedByteBufferDetector);
    }

    /**
     * Returns the {@link SharedCharsetCoderDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedCharsetCoder = false}
     * @since 1.7.0
     */
    public static SharedCharsetCoderDetector sharedCharsetCoderDetector() {
        return require("detectSharedCharsetCoder", c -> c.sharedCharsetCoderDetector);
    }

    /**
     * Returns the {@link SharedChecksumDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedChecksum = false}
     * @since 1.7.0
     */
    public static SharedChecksumDetector sharedChecksumDetector() {
        return require("detectSharedChecksum", c -> c.sharedChecksumDetector);
    }

    /**
     * Returns the {@link FileChannelPositionRaceDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFileChannelPositionRace = false}
     * @since 1.7.0
     */
    public static FileChannelPositionRaceDetector fileChannelPositionRaceDetector() {
        return require("detectFileChannelPositionRace", c -> c.fileChannelPositionRaceDetector);
    }

    /**
     * Returns the {@link SharedIteratorDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedIterator = false}
     * @since 1.7.0
     */
    public static SharedIteratorDetector sharedIteratorDetector() {
        return require("detectSharedIterator", c -> c.sharedIteratorDetector);
    }

    /**
     * Returns the {@link HighContentionAtomicDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectHighContentionAtomic = false}
     * @since 1.7.0
     */
    public static HighContentionAtomicDetector highContentionAtomicDetector() {
        return require("detectHighContentionAtomic", c -> c.highContentionAtomicDetector);
    }

    /**
     * Returns the {@link SharedJsonMapperReconfigDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedJsonMapperReconfig = false}
     * @since 1.7.0
     */
    public static SharedJsonMapperReconfigDetector sharedJsonMapperReconfigDetector() {
        return require("detectSharedJsonMapperReconfig", c -> c.sharedJsonMapperReconfigDetector);
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
