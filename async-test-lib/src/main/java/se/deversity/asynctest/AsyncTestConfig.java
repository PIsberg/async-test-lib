package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIImmutable;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of all {@link AsyncTest} parameters.
 * Passed to {@link se.deversity.asynctest.runner.ConcurrencyRunner} as a single object
 * instead of an ever-growing parameter list.
 */
@AICore(
    sensitivity = "Critical",
    note = "Adding a new detector requires synchronized changes across six places: the five the DetectorType lock names (@AsyncTest attribute, AsyncTestConfig field, Builder default, build() detectAll/excludes resolution, DetectorRegistry constructor) plus the from(AsyncTest) call chain, which the lock does not count because it belongs to this class, not the enum. Same change, counted from two ends."
)
@AIContext(
    focus = "Maintain strict 1:1 mapping between @AsyncTest attributes, Builder fields, from(AsyncTest), build() logic, and DetectorRegistry",
    avoids = "mutable state — this class must remain immutable after construction"
)
@AIImmutable(note = "Immutable snapshot of @AsyncTest parameters to ensure thread safety.")
@API(status = Status.STABLE)
public final class AsyncTestConfig {

    // ---- Execution ----
    /** Resolved value of {@link AsyncTest#threads()} for this run. */
    public final int threads;
    /** Resolved value of {@link AsyncTest#invocations()} for this run. */
    public final int invocations;
    /** Resolved value of {@link AsyncTest#useVirtualThreads()} for this run. */
    public final boolean useVirtualThreads;
    /** Resolved value of {@link AsyncTest#timeoutMs()} for this run. */
    public final long timeoutMs;
    /** Resolved value of {@link AsyncTest#virtualThreadStressMode()} for this run. */
    public final String virtualThreadStressMode;

    // ---- Umbrella flag ----
    /** When {@code true}, every detector is treated as enabled. */
    public final boolean detectAll;

    /**
     * Replay seed configured on the annotation (0 = generate per invocation).
     * The actual per-invocation seed used at runtime is on {@link AsyncTestContext#replaySeed()}.
     */
    public final long replaySeed;

    /**
     * Severity threshold at or above which detector findings fail the test.
     * {@link FailOn#NONE} (default) keeps the legacy report-only behavior.
     * @since 1.7.0
     */
    public final FailOn failOn;

    // ---- Phase 1 ----
    /** Resolved value of {@link AsyncTest#detectDeadlocks()} for this run. */
    public final boolean detectDeadlocks;
    /** Resolved value of {@link AsyncTest#detectVisibility()} for this run. */
    public final boolean detectVisibility;
    /** Resolved value of {@link AsyncTest#detectLivelocks()} for this run. */
    public final boolean detectLivelocks;

    // ---- Phase 2 ----
    /** Resolved value of {@link AsyncTest#detectFalseSharing()} for this run. */
    public final boolean detectFalseSharing;
    /** Resolved value of {@link AsyncTest#detectWakeupIssues()} for this run. */
    public final boolean detectWakeupIssues;
    /** Resolved value of {@link AsyncTest#validateConstructorSafety()} for this run. */
    public final boolean validateConstructorSafety;
    /** Resolved value of {@link AsyncTest#detectABAProblem()} for this run. */
    public final boolean detectABAProblem;
    /** Resolved value of {@link AsyncTest#validateLockOrder()} for this run. */
    public final boolean validateLockOrder;
    /** Resolved value of {@link AsyncTest#monitorSynchronizers()} for this run. */
    public final boolean monitorSynchronizers;
    /** Resolved value of {@link AsyncTest#monitorThreadPool()} for this run. */
    public final boolean monitorThreadPool;
    /** Resolved value of {@link AsyncTest#detectMemoryOrderingViolations()} for this run. */
    public final boolean detectMemoryOrderingViolations;
    /** Resolved value of {@link AsyncTest#monitorAsyncPipeline()} for this run. */
    public final boolean monitorAsyncPipeline;
    /** Resolved value of {@link AsyncTest#monitorReadWriteLockFairness()} for this run. */
    public final boolean monitorReadWriteLockFairness;

    // ---- Phase 3 ----
    /** Resolved value of {@link AsyncTest#detectRaceConditions()} for this run. */
    public final boolean detectRaceConditions;
    /** Resolved value of {@link AsyncTest#detectThreadLocalLeaks()} for this run. */
    public final boolean detectThreadLocalLeaks;
    /** Resolved value of {@link AsyncTest#detectBusyWaiting()} for this run. */
    public final boolean detectBusyWaiting;
    /** Resolved value of {@link AsyncTest#detectAtomicityViolations()} for this run. */
    public final boolean detectAtomicityViolations;
    /** Resolved value of {@link AsyncTest#detectInterruptMishandling()} for this run. */
    public final boolean detectInterruptMishandling;

    // ---- Phase 2 Additional ----
    /** Resolved value of {@link AsyncTest#monitorSemaphore()} for this run. */
    public final boolean monitorSemaphore;
    /** Resolved value of {@link AsyncTest#detectCompletableFutureExceptions()} for this run. */
    public final boolean detectCompletableFutureExceptions;
    /** Resolved value of {@link AsyncTest#detectCompletableFutureCompletionLeaks()} for this run. */
    public final boolean detectCompletableFutureCompletionLeaks;
    /** Resolved value of {@link AsyncTest#detectVirtualThreadPinning()} for this run. */
    public final boolean detectVirtualThreadPinning;
    /** Resolved value of {@link AsyncTest#detectThreadPoolDeadlocks()} for this run. */
    public final boolean detectThreadPoolDeadlocks;
    /** Resolved value of {@link AsyncTest#detectConcurrentModifications()} for this run. */
    public final boolean detectConcurrentModifications;
    /** Resolved value of {@link AsyncTest#detectLockLeaks()} for this run. */
    public final boolean detectLockLeaks;
    /** Resolved value of {@link AsyncTest#detectSharedRandom()} for this run. */
    public final boolean detectSharedRandom;
    /** Resolved value of {@link AsyncTest#detectBlockingQueueIssues()} for this run. */
    public final boolean detectBlockingQueueIssues;
    /** Resolved value of {@link AsyncTest#detectConditionVariableIssues()} for this run. */
    public final boolean detectConditionVariableIssues;
    /** Resolved value of {@link AsyncTest#detectSimpleDateFormatIssues()} for this run. */
    public final boolean detectSimpleDateFormatIssues;
    /** Resolved value of {@link AsyncTest#detectParallelStreamIssues()} for this run. */
    public final boolean detectParallelStreamIssues;
    /** Resolved value of {@link AsyncTest#detectResourceLeaks()} for this run. */
    public final boolean detectResourceLeaks;

    // ---- Phase 2: Additional Concurrency ----
    /** Resolved value of {@link AsyncTest#detectCountDownLatchIssues()} for this run. */
    public final boolean detectCountDownLatchIssues;
    /** Resolved value of {@link AsyncTest#detectCyclicBarrierIssues()} for this run. */
    public final boolean detectCyclicBarrierIssues;
    /** Resolved value of {@link AsyncTest#detectReentrantLockIssues()} for this run. */
    public final boolean detectReentrantLockIssues;
    /** Resolved value of {@link AsyncTest#detectVolatileArrayIssues()} for this run. */
    public final boolean detectVolatileArrayIssues;
    /** Resolved value of {@link AsyncTest#detectDoubleCheckedLocking()} for this run. */
    public final boolean detectDoubleCheckedLocking;
    /** Resolved value of {@link AsyncTest#detectWaitTimeout()} for this run. */
    public final boolean detectWaitTimeout;
    /** Resolved value of {@link AsyncTest#detectLockContention()} for this run. */
    public final boolean detectLockContention;
    /** Resolved value of {@link AsyncTest#detectSynchronizedNonFinal()} for this run. */
    public final boolean detectSynchronizedNonFinal;
    /** Resolved value of {@link AsyncTest#detectMissedSignals()} for this run. */
    public final boolean detectMissedSignals;
    /** Resolved value of {@link AsyncTest#detectLazyInitRace()} for this run. */
    public final boolean detectLazyInitRace;

    // ---- Phase 2: Advanced Concurrency Utilities ----
    /** Resolved value of {@link AsyncTest#detectPhaserIssues()} for this run. */
    public final boolean detectPhaserIssues;
    /** Resolved value of {@link AsyncTest#detectStampedLockIssues()} for this run. */
    public final boolean detectStampedLockIssues;
    /** Resolved value of {@link AsyncTest#detectExchangerIssues()} for this run. */
    public final boolean detectExchangerIssues;
    /** Resolved value of {@link AsyncTest#detectScheduledExecutorIssues()} for this run. */
    public final boolean detectScheduledExecutorIssues;
    /** Resolved value of {@link AsyncTest#detectForkJoinPoolIssues()} for this run. */
    public final boolean detectForkJoinPoolIssues;
    /** Resolved value of {@link AsyncTest#detectThreadFactoryIssues()} for this run. */
    public final boolean detectThreadFactoryIssues;
    /** Resolved value of {@link AsyncTest#detectThreadLeaks()} for this run. */
    public final boolean detectThreadLeaks;
    /** Resolved value of {@link AsyncTest#detectSleepInLock()} for this run. */
    public final boolean detectSleepInLock;
    /** Resolved value of {@link AsyncTest#detectUnboundedQueue()} for this run. */
    public final boolean detectUnboundedQueue;
    /** Resolved value of {@link AsyncTest#detectThreadStarvation()} for this run. */
    public final boolean detectThreadStarvation;

    // ---- Phase 5: Thread-Safety of Common Types ----
    /** Resolved value of {@link AsyncTest#detectCalendarIssues()} for this run. */
    public final boolean detectCalendarIssues;
    /** Resolved value of {@link AsyncTest#detectSharedCollections()} for this run. */
    public final boolean detectSharedCollections;
    /** Resolved value of {@link AsyncTest#detectTimerIssues()} for this run. */
    public final boolean detectTimerIssues;
    /** Resolved value of {@link AsyncTest#detectCopyOnWriteCollectionIssues()} for this run. */
    public final boolean detectCopyOnWriteCollectionIssues;
    /** Resolved value of {@link AsyncTest#detectStringBuilderIssues()} for this run. */
    public final boolean detectStringBuilderIssues;

    // ---- Phase 6: Virtual Thread Concurrency (Java 21+) ----
    /** Resolved value of {@link AsyncTest#detectStructuredConcurrencyIssues()} for this run. */
    public final boolean detectStructuredConcurrencyIssues;
    /** Resolved value of {@link AsyncTest#detectVirtualThreadContextLeaks()} for this run. */
    public final boolean detectVirtualThreadContextLeaks;
    /** Resolved value of {@link AsyncTest#detectScopedValueMisuse()} for this run. */
    public final boolean detectScopedValueMisuse;
    /** Resolved value of {@link AsyncTest#detectVirtualThreadCpuBoundTasks()} for this run. */
    public final boolean detectVirtualThreadCpuBoundTasks;
    /** Resolved value of {@link AsyncTest#detectVirtualThreadCarrierExhaustion()} for this run. */
    public final boolean detectVirtualThreadCarrierExhaustion;

    // ---- Phase 7: High-Level Concurrency Patterns ----
    /** Resolved value of {@link AsyncTest#detectHttpClientIssues()} for this run. */
    public final boolean detectHttpClientIssues;
    /** Resolved value of {@link AsyncTest#detectStreamClosing()} for this run. */
    public final boolean detectStreamClosing;
    /** Resolved value of {@link AsyncTest#detectCacheConcurrency()} for this run. */
    public final boolean detectCacheConcurrency;
    /** Resolved value of {@link AsyncTest#detectCompletableFutureChainIssues()} for this run. */
    public final boolean detectCompletableFutureChainIssues;

    // ---- Phase 8: Lifecycle & Structural Correctness ----
    /** Resolved value of {@link AsyncTest#detectExecutorShutdown()} for this run. */
    public final boolean detectExecutorShutdown;
    /** Resolved value of {@link AsyncTest#detectMutableMapKeys()} for this run. */
    public final boolean detectMutableMapKeys;
    /** Resolved value of {@link AsyncTest#detectNestedMonitorLockout()} for this run. */
    public final boolean detectNestedMonitorLockout;
    /** Resolved value of {@link AsyncTest#detectLockDowngrade()} for this run. */
    public final boolean detectLockDowngrade;
    /** Resolved value of {@link AsyncTest#detectInheritableThreadLocalMisuse()} for this run. */
    public final boolean detectInheritableThreadLocalMisuse;

    // ---- Phase 10: API Traps & Subtle Concurrency Bugs ----
    /** Resolved value of {@link AsyncTest#detectThreadLocalContamination()} for this run. */
    public final boolean detectThreadLocalContamination;
    /** Resolved value of {@link AsyncTest#detectAtomicNonAtomicUpdates()} for this run. */
    public final boolean detectAtomicNonAtomicUpdates;
    /** Resolved value of {@link AsyncTest#detectSynchronizedCollectionIteration()} for this run. */
    public final boolean detectSynchronizedCollectionIteration;
    /** Resolved value of {@link AsyncTest#detectSharedFormatter()} for this run. */
    public final boolean detectSharedFormatter;
    /** Resolved value of {@link AsyncTest#detectConcurrentMapComputeRecursion()} for this run. */
    public final boolean detectConcurrentMapComputeRecursion;
    /** Resolved value of {@link AsyncTest#detectSynchronizedOnLiteral()} for this run. */
    public final boolean detectSynchronizedOnLiteral;
    /** Resolved value of {@link AsyncTest#detectPublicLockExposure()} for this run. */
    public final boolean detectPublicLockExposure;
    /** Resolved value of {@link AsyncTest#detectForkJoinTaskBlocking()} for this run. */
    public final boolean detectForkJoinTaskBlocking;
    /** Resolved value of {@link AsyncTest#detectOptimisticReadValidation()} for this run. */
    public final boolean detectOptimisticReadValidation;
    /** Resolved value of {@link AsyncTest#detectCFCommonPoolBlocking()} for this run. */
    public final boolean detectCFCommonPoolBlocking;

    // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----
    /** Resolved value of {@link AsyncTest#detectSharedMatcher()} for this run. */
    public final boolean detectSharedMatcher;
    /** Resolved value of {@link AsyncTest#detectSharedDecimalFormat()} for this run. */
    public final boolean detectSharedDecimalFormat;
    /** Resolved value of {@link AsyncTest#detectWeakReferenceRace()} for this run. */
    public final boolean detectWeakReferenceRace;
    /** Resolved value of {@link AsyncTest#detectStatefulLambda()} for this run. */
    public final boolean detectStatefulLambda;
    /** Resolved value of {@link AsyncTest#detectSharedMessageDigest()} for this run. */
    public final boolean detectSharedMessageDigest;

    // ---- Phase 12: Operational & Hygiene Concurrency Issues ----
    /** Resolved value of {@link AsyncTest#detectInterruptSwallowing()} for this run. */
    public final boolean detectInterruptSwallowing;
    /** Resolved value of {@link AsyncTest#detectMdcContextLeak()} for this run. */
    public final boolean detectMdcContextLeak;
    /** Resolved value of {@link AsyncTest#detectSystemPropertyMutation()} for this run. */
    public final boolean detectSystemPropertyMutation;
    /** Resolved value of {@link AsyncTest#detectFutureIgnored()} for this run. */
    public final boolean detectFutureIgnored;
    /** Resolved value of {@link AsyncTest#detectExplicitGc()} for this run. */
    public final boolean detectExplicitGc;
    /** Resolved value of {@link AsyncTest#detectDeprecatedThreadApi()} for this run. */
    public final boolean detectDeprecatedThreadApi;
    /** Resolved value of {@link AsyncTest#detectSharedXmlParser()} for this run. */
    public final boolean detectSharedXmlParser;
    /** Resolved value of {@link AsyncTest#detectBoxedPrimitiveLock()} for this run. */
    public final boolean detectBoxedPrimitiveLock;
    /** Resolved value of {@link AsyncTest#detectSharedTimeZone()} for this run. */
    public final boolean detectSharedTimeZone;
    /** Resolved value of {@link AsyncTest#detectUncaughtExceptionHandler()} for this run. */
    public final boolean detectUncaughtExceptionHandler;

    // ---- Phase 13 (1.0.0+) ----
    /** Resolved value of {@link AsyncTest#detectDaemonThreadHygiene()} for this run. */
    public final boolean detectDaemonThreadHygiene;
    /** Resolved value of {@link AsyncTest#detectNotifyWithoutMonitor()} for this run. */
    public final boolean detectNotifyWithoutMonitor;
    /** Resolved value of {@link AsyncTest#detectSharedSecureRandom()} for this run. */
    public final boolean detectSharedSecureRandom;
    /** Resolved value of {@link AsyncTest#detectWeakHashMapShared()} for this run. */
    public final boolean detectWeakHashMapShared;
    /** Resolved value of {@link AsyncTest#detectJdbcConnectionShared()} for this run. */
    public final boolean detectJdbcConnectionShared;

    // ---- Phase 14 (1.7.0+) ----
    /** Resolved value of {@link AsyncTest#detectSharedStatefulCrypto()} for this run. */
    public final boolean detectSharedStatefulCrypto;
    /** Resolved value of {@link AsyncTest#detectConcurrentMapCheckThenAct()} for this run. */
    public final boolean detectConcurrentMapCheckThenAct;
    /** Resolved value of {@link AsyncTest#detectSharedDeflater()} for this run. */
    public final boolean detectSharedDeflater;
    /** Resolved value of {@link AsyncTest#detectThisEscape()} for this run. */
    public final boolean detectThisEscape;
    /** Resolved value of {@link AsyncTest#detectThreadLocalRandomMisuse()} for this run. */
    public final boolean detectThreadLocalRandomMisuse;

    // ---- Phase 15 (1.8.0+) ----
    /** Resolved value of {@link AsyncTest#detectCompletableFutureObtrudeAbuse()} for this run. */
    public final boolean detectCompletableFutureObtrudeAbuse;
    /** Resolved value of {@link AsyncTest#detectSpuriousWakeupHazard()} for this run. */
    public final boolean detectSpuriousWakeupHazard;
    /** Resolved value of {@link AsyncTest#detectLockUpgradeDeadlock()} for this run. */
    public final boolean detectLockUpgradeDeadlock;
    /** Resolved value of {@link AsyncTest#detectTryLockMisuse()} for this run. */
    public final boolean detectTryLockMisuse;
    /** Resolved value of {@link AsyncTest#detectCFBlockingCallback()} for this run. */
    public final boolean detectCFBlockingCallback;

    // ---- Phase 16: JDK 25/26 preview-era detectors ----
    /** Resolved value of {@link AsyncTest#detectStableValueMisuse()} for this run. */
    public final boolean detectStableValueMisuse;
    /** Resolved value of {@link AsyncTest#detectStructuredTaskScopeMisuse()} for this run. */
    public final boolean detectStructuredTaskScopeMisuse;
    /** Resolved value of {@link AsyncTest#detectGathererConcurrencyMisuse()} for this run. */
    public final boolean detectGathererConcurrencyMisuse;

    // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----
    /** Resolved value of {@link AsyncTest#detectSharedByteBuffer()} for this run. */
    public final boolean detectSharedByteBuffer;
    /** Resolved value of {@link AsyncTest#detectSharedCharsetCoder()} for this run. */
    public final boolean detectSharedCharsetCoder;
    /** Resolved value of {@link AsyncTest#detectSharedChecksum()} for this run. */
    public final boolean detectSharedChecksum;
    /** Resolved value of {@link AsyncTest#detectFileChannelPositionRace()} for this run. */
    public final boolean detectFileChannelPositionRace;
    /** Resolved value of {@link AsyncTest#detectSharedIterator()} for this run. */
    public final boolean detectSharedIterator;
    /** Resolved value of {@link AsyncTest#detectHighContentionAtomic()} for this run. */
    public final boolean detectHighContentionAtomic;
    /** Resolved value of {@link AsyncTest#detectSharedJsonMapperReconfig()} for this run. */
    public final boolean detectSharedJsonMapperReconfig;

    // ---- Phase 18: JDK 25/26 GA-era concurrency detectors ----
    /** Resolved value of {@link AsyncTest#detectLazyConstantMisuse()} for this run. */
    public final boolean detectLazyConstantMisuse;
    /** Resolved value of {@link AsyncTest#detectFinalFieldMutation()} for this run. */
    public final boolean detectFinalFieldMutation;
    /** Resolved value of {@link AsyncTest#detectSharedKdf()} for this run. */
    public final boolean detectSharedKdf;
    /** Resolved value of {@link AsyncTest#detectLatchMisuse()} for this run. */
    public final boolean detectLatchMisuse;
    /** Resolved value of {@link AsyncTest#detectExecutorDeadlock()} for this run. */
    public final boolean detectExecutorDeadlock;
    /** Resolved value of {@link AsyncTest#detectFutureBlocking()} for this run. */
    public final boolean detectFutureBlocking;
    /** Resolved value of {@link AsyncTest#detectFlowPublisherConcurrency()} for this run. */
    public final boolean detectFlowPublisherConcurrency;
    /** Resolved value of {@link AsyncTest#detectConfinedArenaThreadEscape()} for this run. */
    public final boolean detectConfinedArenaThreadEscape;
    /** Resolved value of {@link AsyncTest#detectSharedMemorySegmentRace()} for this run. */
    public final boolean detectSharedMemorySegmentRace;
    /** Resolved value of {@link AsyncTest#detectVarHandleNonAtomicUpdate()} for this run. */
    public final boolean detectVarHandleNonAtomicUpdate;
    /** Resolved value of {@link AsyncTest#detectRecordMutableComponentLeak()} for this run. */
    public final boolean detectRecordMutableComponentLeak;
    /** Resolved value of {@link AsyncTest#detectStaticInitDeadlock()} for this run. */
    public final boolean detectStaticInitDeadlock;
    /** Resolved value of {@link AsyncTest#detectVirtualThreadPooling()} for this run. */
    public final boolean detectVirtualThreadPooling;
    /** Resolved value of {@link AsyncTest#detectPlatformThreadPerTask()} for this run. */
    public final boolean detectPlatformThreadPerTask;
    /** Resolved value of {@link AsyncTest#detectSharedSplittableRandom()} for this run. */
    public final boolean detectSharedSplittableRandom;

    // ---- Benchmarking ----
    /** Resolved value of {@link AsyncTest#enableBenchmarking()} for this run. */
    @AIFeatureFlag(flag = "async-test.benchmarking.enabled", defaultValue = false)
    public final boolean enableBenchmarking;
    /** Resolved value of {@link AsyncTest#benchmarkRegressionThreshold()} for this run. */
    public final double benchmarkRegressionThreshold;
    /** Resolved value of {@link AsyncTest#failOnBenchmarkRegression()} for this run. */
    public final boolean failOnBenchmarkRegression;

    // ---- License Gating ----
    /** Resolved value of {@link AsyncTest#keygenAccountId()} for this run. */
    public final String keygenAccountId;
    /** Resolved value of {@link AsyncTest#keygenApiKey()} for this run. */
    public final String keygenApiKey;
    /** Resolved value of {@link AsyncTest#keygenProductId()} for this run. */
    public final String keygenProductId;
    /** Resolved value of {@link AsyncTest#lemonSqueezyStore()} for this run. */
    public final String lemonSqueezyStore;
    /** Resolved value of {@link AsyncTest#licenseKey()} for this run. */
    public final String licenseKey;
    /** Resolved value of {@link AsyncTest#licenseMockMode()} for this run. */
    @AIFeatureFlag(flag = "license.mock.mode", defaultValue = false)
    public final boolean licenseMockMode;

    private AsyncTestConfig(Builder b) {
        threads                        = b.threads;
        invocations                    = b.invocations;
        useVirtualThreads              = b.useVirtualThreads;
        timeoutMs                      = b.timeoutMs;
        virtualThreadStressMode        = b.virtualThreadStressMode;
        detectAll                      = b.detectAll;
        replaySeed                     = b.replaySeed;
        failOn                         = b.failOn;
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
        detectLockContention           = b.detectLockContention;
        detectSynchronizedNonFinal     = b.detectSynchronizedNonFinal;
        detectMissedSignals            = b.detectMissedSignals;
        detectLazyInitRace             = b.detectLazyInitRace;
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
        detectStructuredConcurrencyIssues    = b.detectStructuredConcurrencyIssues;
        detectVirtualThreadContextLeaks      = b.detectVirtualThreadContextLeaks;
        detectScopedValueMisuse              = b.detectScopedValueMisuse;
        detectVirtualThreadCpuBoundTasks     = b.detectVirtualThreadCpuBoundTasks;
        detectVirtualThreadCarrierExhaustion = b.detectVirtualThreadCarrierExhaustion;
        detectHttpClientIssues           = b.detectHttpClientIssues;
        detectStreamClosing              = b.detectStreamClosing;
        detectCacheConcurrency           = b.detectCacheConcurrency;
        detectCompletableFutureChainIssues = b.detectCompletableFutureChainIssues;
        detectExecutorShutdown           = b.detectExecutorShutdown;
        detectMutableMapKeys             = b.detectMutableMapKeys;
        detectNestedMonitorLockout       = b.detectNestedMonitorLockout;
        detectLockDowngrade              = b.detectLockDowngrade;
        detectInheritableThreadLocalMisuse = b.detectInheritableThreadLocalMisuse;
        detectThreadLocalContamination     = b.detectThreadLocalContamination;
        detectAtomicNonAtomicUpdates       = b.detectAtomicNonAtomicUpdates;
        detectSynchronizedCollectionIteration = b.detectSynchronizedCollectionIteration;
        detectSharedFormatter              = b.detectSharedFormatter;
        detectConcurrentMapComputeRecursion = b.detectConcurrentMapComputeRecursion;
        detectSynchronizedOnLiteral        = b.detectSynchronizedOnLiteral;
        detectPublicLockExposure           = b.detectPublicLockExposure;
        detectForkJoinTaskBlocking         = b.detectForkJoinTaskBlocking;
        detectOptimisticReadValidation     = b.detectOptimisticReadValidation;
        detectCFCommonPoolBlocking         = b.detectCFCommonPoolBlocking;
        detectSharedMatcher            = b.detectSharedMatcher;
        detectSharedDecimalFormat      = b.detectSharedDecimalFormat;
        detectWeakReferenceRace        = b.detectWeakReferenceRace;
        detectStatefulLambda           = b.detectStatefulLambda;
        detectSharedMessageDigest      = b.detectSharedMessageDigest;
        detectInterruptSwallowing      = b.detectInterruptSwallowing;
        detectMdcContextLeak           = b.detectMdcContextLeak;
        detectSystemPropertyMutation   = b.detectSystemPropertyMutation;
        detectFutureIgnored            = b.detectFutureIgnored;
        detectExplicitGc               = b.detectExplicitGc;
        detectDeprecatedThreadApi      = b.detectDeprecatedThreadApi;
        detectSharedXmlParser          = b.detectSharedXmlParser;
        detectBoxedPrimitiveLock       = b.detectBoxedPrimitiveLock;
        detectSharedTimeZone           = b.detectSharedTimeZone;
        detectUncaughtExceptionHandler = b.detectUncaughtExceptionHandler;
        // Phase 13
        detectDaemonThreadHygiene   = b.detectDaemonThreadHygiene;
        detectNotifyWithoutMonitor  = b.detectNotifyWithoutMonitor;
        detectSharedSecureRandom    = b.detectSharedSecureRandom;
        detectWeakHashMapShared     = b.detectWeakHashMapShared;
        detectJdbcConnectionShared  = b.detectJdbcConnectionShared;
        // Phase 14
        detectSharedStatefulCrypto      = b.detectSharedStatefulCrypto;
        detectConcurrentMapCheckThenAct = b.detectConcurrentMapCheckThenAct;
        detectSharedDeflater            = b.detectSharedDeflater;
        detectThisEscape                = b.detectThisEscape;
        detectThreadLocalRandomMisuse   = b.detectThreadLocalRandomMisuse;
        // Phase 15
        detectCompletableFutureObtrudeAbuse = b.detectCompletableFutureObtrudeAbuse;
        detectSpuriousWakeupHazard          = b.detectSpuriousWakeupHazard;
        detectLockUpgradeDeadlock           = b.detectLockUpgradeDeadlock;
        detectTryLockMisuse                 = b.detectTryLockMisuse;
        detectCFBlockingCallback            = b.detectCFBlockingCallback;
        // Phase 16 (JDK 25/26)
        detectStableValueMisuse             = b.detectStableValueMisuse;
        detectStructuredTaskScopeMisuse     = b.detectStructuredTaskScopeMisuse;
        detectGathererConcurrencyMisuse     = b.detectGathererConcurrencyMisuse;
        // Phase 17
        detectSharedByteBuffer          = b.detectSharedByteBuffer;
        detectSharedCharsetCoder        = b.detectSharedCharsetCoder;
        detectSharedChecksum            = b.detectSharedChecksum;
        detectFileChannelPositionRace   = b.detectFileChannelPositionRace;
        detectSharedIterator            = b.detectSharedIterator;
        detectHighContentionAtomic      = b.detectHighContentionAtomic;
        detectSharedJsonMapperReconfig  = b.detectSharedJsonMapperReconfig;
        // Phase 18 (JDK 25/26 GA)
        detectLazyConstantMisuse        = b.detectLazyConstantMisuse;
        detectFinalFieldMutation        = b.detectFinalFieldMutation;
        detectSharedKdf                 = b.detectSharedKdf;
        detectLatchMisuse               = b.detectLatchMisuse;
        detectExecutorDeadlock          = b.detectExecutorDeadlock;
        detectFutureBlocking            = b.detectFutureBlocking;
        detectFlowPublisherConcurrency  = b.detectFlowPublisherConcurrency;
        detectConfinedArenaThreadEscape  = b.detectConfinedArenaThreadEscape;
        detectSharedMemorySegmentRace    = b.detectSharedMemorySegmentRace;
        detectVarHandleNonAtomicUpdate   = b.detectVarHandleNonAtomicUpdate;
        detectRecordMutableComponentLeak = b.detectRecordMutableComponentLeak;
        detectStaticInitDeadlock         = b.detectStaticInitDeadlock;
        detectVirtualThreadPooling       = b.detectVirtualThreadPooling;
        detectPlatformThreadPerTask      = b.detectPlatformThreadPerTask;
        detectSharedSplittableRandom     = b.detectSharedSplittableRandom;
        enableBenchmarking             = b.enableBenchmarking;
        benchmarkRegressionThreshold   = b.benchmarkRegressionThreshold;
        failOnBenchmarkRegression      = b.failOnBenchmarkRegression;
        keygenAccountId                = b.keygenAccountId;
        keygenApiKey                   = b.keygenApiKey;
        keygenProductId                = b.keygenProductId;
        lemonSqueezyStore              = b.lemonSqueezyStore;
        licenseKey                     = b.licenseKey;
        licenseMockMode                = b.licenseMockMode;
    }

    /**
     * Builds a config from an {@link AsyncTest} annotation instance.
     *
     * @param ann the annotation instance to read the declared values from
     * @return the resolved configuration for this run
     */
    public static AsyncTestConfig from(AsyncTest ann) {
        return from(ann, ann.threads());
    }

    /**
     * Builds a config from an {@link AsyncTest} annotation instance, overriding
     * the thread count. Used by the schedule-matrix path in {@code @AsyncTest(threadCounts=...)}
     * so that each matrix entry runs with its own thread count while sharing all
     * other annotation fields.
     *
     * @since 1.6.0
     *
     * @param ann the annotation instance to read the declared values from
     * @param threadsOverride thread count to use instead of {@link AsyncTest#threads()}, as supplied by a parameterised template
     * @return the resolved configuration for this run
     */
    public static AsyncTestConfig from(AsyncTest ann, int threadsOverride) {
        // Check for global benchmarking system property
        boolean globalBenchmarkingEnabled = Boolean.getBoolean("async-test.benchmarking.enabled");

        // Resolve includes/preset → effective detectAll + excludes set.
        // A non-empty includes() is the most specific selection and wins over
        // preset() and detectAll(): detectAll is forced true and every
        // DetectorType outside the include set is excluded, so build()'s
        // detectAll loop activates only the listed detectors. Otherwise
        // ALL/STRICT preserve the legacy detectAll behavior, and other presets
        // exclude everything outside the preset's enabled set the same way.
        // User-supplied excludes() always layer on top and win on conflict.
        Preset preset = ann.preset();
        boolean effectiveDetectAll;
        Set<DetectorType> effectiveExcludes = EnumSet.noneOf(DetectorType.class);
        if (ann.includes().length > 0) {
            effectiveDetectAll = true;
            Set<DetectorType> included = EnumSet.noneOf(DetectorType.class);
            included.addAll(Arrays.asList(ann.includes()));
            for (DetectorType t : DetectorType.values()) {
                if (!included.contains(t)) effectiveExcludes.add(t);
            }
        } else if (preset.isAll()) {
            effectiveDetectAll = ann.detectAll();
        } else {
            effectiveDetectAll = true;
            // Non-null here: the isAll() branch above owns every preset whose set is null.
            Set<DetectorType> enabled = Objects.requireNonNull(
                preset.enabled(), "non-all preset must enumerate its detectors");
            for (DetectorType t : DetectorType.values()) {
                if (!enabled.contains(t)) effectiveExcludes.add(t);
            }
        }
        effectiveExcludes.addAll(Arrays.asList(ann.excludes()));

        return builder()
            .threads(threadsOverride)
            .invocations(ann.invocations())
            .useVirtualThreads(ann.useVirtualThreads())
            .timeoutMs(ann.timeoutMs())
            .virtualThreadStressMode(ann.virtualThreadStressMode())
            .detectAll(effectiveDetectAll)
            .replaySeed(ann.replaySeed())
            .failOn(ann.failOn())
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
            .detectLockContention(ann.detectLockContention())
            .detectSynchronizedNonFinal(ann.detectSynchronizedNonFinal())
            .detectMissedSignals(ann.detectMissedSignals())
            .detectLazyInitRace(ann.detectLazyInitRace())
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
            .detectVirtualThreadCpuBoundTasks(ann.detectVirtualThreadCpuBoundTasks())
            .detectVirtualThreadCarrierExhaustion(ann.detectVirtualThreadCarrierExhaustion())
            .detectHttpClientIssues(ann.detectHttpClientIssues())
            .detectStreamClosing(ann.detectStreamClosing())
            .detectCacheConcurrency(ann.detectCacheConcurrency())
            .detectCompletableFutureChainIssues(ann.detectCompletableFutureChainIssues())
            .detectExecutorShutdown(ann.detectExecutorShutdown())
            .detectMutableMapKeys(ann.detectMutableMapKeys())
            .detectNestedMonitorLockout(ann.detectNestedMonitorLockout())
            .detectLockDowngrade(ann.detectLockDowngrade())
            .detectInheritableThreadLocalMisuse(ann.detectInheritableThreadLocalMisuse())
            .detectThreadLocalContamination(ann.detectThreadLocalContamination())
            .detectAtomicNonAtomicUpdates(ann.detectAtomicNonAtomicUpdates())
            .detectSynchronizedCollectionIteration(ann.detectSynchronizedCollectionIteration())
            .detectSharedFormatter(ann.detectSharedFormatter())
            .detectConcurrentMapComputeRecursion(ann.detectConcurrentMapComputeRecursion())
            .detectSynchronizedOnLiteral(ann.detectSynchronizedOnLiteral())
            .detectPublicLockExposure(ann.detectPublicLockExposure())
            .detectForkJoinTaskBlocking(ann.detectForkJoinTaskBlocking())
            .detectOptimisticReadValidation(ann.detectOptimisticReadValidation())
            .detectCFCommonPoolBlocking(ann.detectCFCommonPoolBlocking())
            .detectSharedMatcher(ann.detectSharedMatcher())
            .detectSharedDecimalFormat(ann.detectSharedDecimalFormat())
            .detectWeakReferenceRace(ann.detectWeakReferenceRace())
            .detectStatefulLambda(ann.detectStatefulLambda())
            .detectSharedMessageDigest(ann.detectSharedMessageDigest())
            .detectInterruptSwallowing(ann.detectInterruptSwallowing())
            .detectMdcContextLeak(ann.detectMdcContextLeak())
            .detectSystemPropertyMutation(ann.detectSystemPropertyMutation())
            .detectFutureIgnored(ann.detectFutureIgnored())
            .detectExplicitGc(ann.detectExplicitGc())
            .detectDeprecatedThreadApi(ann.detectDeprecatedThreadApi())
            .detectSharedXmlParser(ann.detectSharedXmlParser())
            .detectBoxedPrimitiveLock(ann.detectBoxedPrimitiveLock())
            .detectSharedTimeZone(ann.detectSharedTimeZone())
            .detectUncaughtExceptionHandler(ann.detectUncaughtExceptionHandler())
            .detectDaemonThreadHygiene(ann.detectDaemonThreadHygiene())
            .detectNotifyWithoutMonitor(ann.detectNotifyWithoutMonitor())
            .detectSharedSecureRandom(ann.detectSharedSecureRandom())
            .detectWeakHashMapShared(ann.detectWeakHashMapShared())
            .detectJdbcConnectionShared(ann.detectJdbcConnectionShared())
            .detectSharedStatefulCrypto(ann.detectSharedStatefulCrypto())
            .detectConcurrentMapCheckThenAct(ann.detectConcurrentMapCheckThenAct())
            .detectSharedDeflater(ann.detectSharedDeflater())
            .detectThisEscape(ann.detectThisEscape())
            .detectThreadLocalRandomMisuse(ann.detectThreadLocalRandomMisuse())
            .detectCompletableFutureObtrudeAbuse(ann.detectCompletableFutureObtrudeAbuse())
            .detectSpuriousWakeupHazard(ann.detectSpuriousWakeupHazard())
            .detectLockUpgradeDeadlock(ann.detectLockUpgradeDeadlock())
            .detectTryLockMisuse(ann.detectTryLockMisuse())
            .detectCFBlockingCallback(ann.detectCFBlockingCallback())
            .detectStableValueMisuse(ann.detectStableValueMisuse())
            .detectStructuredTaskScopeMisuse(ann.detectStructuredTaskScopeMisuse())
            .detectGathererConcurrencyMisuse(ann.detectGathererConcurrencyMisuse())
            .detectSharedByteBuffer(ann.detectSharedByteBuffer())
            .detectSharedCharsetCoder(ann.detectSharedCharsetCoder())
            .detectSharedChecksum(ann.detectSharedChecksum())
            .detectFileChannelPositionRace(ann.detectFileChannelPositionRace())
            .detectSharedIterator(ann.detectSharedIterator())
            .detectHighContentionAtomic(ann.detectHighContentionAtomic())
            .detectSharedJsonMapperReconfig(ann.detectSharedJsonMapperReconfig())
            .detectLazyConstantMisuse(ann.detectLazyConstantMisuse())
            .detectFinalFieldMutation(ann.detectFinalFieldMutation())
            .detectSharedKdf(ann.detectSharedKdf())
            .detectLatchMisuse(ann.detectLatchMisuse())
            .detectExecutorDeadlock(ann.detectExecutorDeadlock())
            .detectFutureBlocking(ann.detectFutureBlocking())
            .detectFlowPublisherConcurrency(ann.detectFlowPublisherConcurrency())
            .detectConfinedArenaThreadEscape(ann.detectConfinedArenaThreadEscape())
            .detectSharedMemorySegmentRace(ann.detectSharedMemorySegmentRace())
            .detectVarHandleNonAtomicUpdate(ann.detectVarHandleNonAtomicUpdate())
            .detectRecordMutableComponentLeak(ann.detectRecordMutableComponentLeak())
            .detectStaticInitDeadlock(ann.detectStaticInitDeadlock())
            .detectVirtualThreadPooling(ann.detectVirtualThreadPooling())
            .detectPlatformThreadPerTask(ann.detectPlatformThreadPerTask())
            .detectSharedSplittableRandom(ann.detectSharedSplittableRandom())
            .enableBenchmarking(ann.enableBenchmarking() || globalBenchmarkingEnabled)
            .benchmarkRegressionThreshold(ann.benchmarkRegressionThreshold())
            .failOnBenchmarkRegression(ann.failOnBenchmarkRegression())
            .keygenAccountId(ann.keygenAccountId())
            .keygenApiKey(ann.keygenApiKey())
            .keygenProductId(ann.keygenProductId())
            .lemonSqueezyStore(ann.lemonSqueezyStore())
            .licenseKey(ann.licenseKey())
            .licenseMockMode(ann.licenseMockMode())
            .excludes(effectiveExcludes.toArray(new DetectorType[0]))
            .build();
    }

    /**
     * {@return a new builder initialised with the library defaults}
     */
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
        private long    replaySeed                 = 0L;
        private FailOn  failOn                     = FailOn.NONE;
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
        private boolean detectLockContention = false;
        private boolean detectSynchronizedNonFinal = false;
        private boolean detectMissedSignals = false;
        private boolean detectLazyInitRace = false;
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
        private boolean detectVirtualThreadCpuBoundTasks = false;
        private boolean detectVirtualThreadCarrierExhaustion = false;
        private boolean detectHttpClientIssues = false;
        private boolean detectStreamClosing = false;
        private boolean detectCacheConcurrency = false;
        private boolean detectCompletableFutureChainIssues = false;
        private boolean detectExecutorShutdown = false;
        private boolean detectMutableMapKeys = false;
        private boolean detectNestedMonitorLockout = false;
        private boolean detectLockDowngrade = false;
        private boolean detectInheritableThreadLocalMisuse = false;
        private boolean detectThreadLocalContamination     = false;
        private boolean detectAtomicNonAtomicUpdates       = false;
        private boolean detectSynchronizedCollectionIteration = false;
        private boolean detectSharedFormatter              = false;
        private boolean detectConcurrentMapComputeRecursion = false;
        private boolean detectSynchronizedOnLiteral        = false;
        private boolean detectPublicLockExposure           = false;
        private boolean detectForkJoinTaskBlocking         = false;
        private boolean detectOptimisticReadValidation     = false;
        private boolean detectCFCommonPoolBlocking         = false;
        private boolean detectSharedMatcher            = false;
        private boolean detectSharedDecimalFormat      = false;
        private boolean detectWeakReferenceRace        = false;
        private boolean detectStatefulLambda           = false;
        private boolean detectSharedMessageDigest      = false;
        private boolean detectInterruptSwallowing      = false;
        private boolean detectMdcContextLeak           = false;
        private boolean detectSystemPropertyMutation   = false;
        private boolean detectFutureIgnored            = false;
        private boolean detectExplicitGc               = false;
        private boolean detectDeprecatedThreadApi      = false;
        private boolean detectSharedXmlParser          = false;
        private boolean detectBoxedPrimitiveLock       = false;
        private boolean detectSharedTimeZone           = false;
        private boolean detectUncaughtExceptionHandler = false;
        // Phase 13
        private boolean detectDaemonThreadHygiene  = false;
        private boolean detectNotifyWithoutMonitor = false;
        private boolean detectSharedSecureRandom   = false;
        private boolean detectWeakHashMapShared    = false;
        private boolean detectJdbcConnectionShared = false;
        // Phase 14
        private boolean detectSharedStatefulCrypto      = false;
        private boolean detectConcurrentMapCheckThenAct = false;
        private boolean detectSharedDeflater            = false;
        private boolean detectThisEscape                = false;
        private boolean detectThreadLocalRandomMisuse   = false;
        // Phase 15
        private boolean detectCompletableFutureObtrudeAbuse = false;
        private boolean detectSpuriousWakeupHazard          = false;
        private boolean detectLockUpgradeDeadlock           = false;
        private boolean detectTryLockMisuse                 = false;
        private boolean detectCFBlockingCallback            = false;
        // Phase 16 (JDK 25/26)
        private boolean detectStableValueMisuse             = false;
        private boolean detectStructuredTaskScopeMisuse     = false;
        private boolean detectGathererConcurrencyMisuse     = false;
        // Phase 17
        private boolean detectSharedByteBuffer          = false;
        private boolean detectSharedCharsetCoder        = false;
        private boolean detectSharedChecksum            = false;
        private boolean detectFileChannelPositionRace   = false;
        private boolean detectSharedIterator            = false;
        private boolean detectHighContentionAtomic      = false;
        private boolean detectSharedJsonMapperReconfig  = false;
        // Phase 18 (JDK 25/26 GA)
        private boolean detectLazyConstantMisuse        = false;
        private boolean detectFinalFieldMutation        = false;
        private boolean detectSharedKdf                 = false;
        private boolean detectLatchMisuse               = false;
        private boolean detectExecutorDeadlock          = false;
        private boolean detectFutureBlocking            = false;
        private boolean detectFlowPublisherConcurrency  = false;
        private boolean detectConfinedArenaThreadEscape  = false;
        private boolean detectSharedMemorySegmentRace    = false;
        private boolean detectVarHandleNonAtomicUpdate   = false;
        private boolean detectRecordMutableComponentLeak = false;
        private boolean detectStaticInitDeadlock         = false;
        private boolean detectVirtualThreadPooling       = false;
        private boolean detectPlatformThreadPerTask      = false;
        private boolean detectSharedSplittableRandom     = false;
        private boolean enableBenchmarking = false;
        private double benchmarkRegressionThreshold = 0.2;
        private boolean failOnBenchmarkRegression = false;
        private String keygenAccountId = "";
        private String keygenApiKey = "";
        private String keygenProductId = "";
        private String lemonSqueezyStore = "";
        private String licenseKey = "";
        private boolean licenseMockMode = false;
        private Set<DetectorType> excludes = EnumSet.noneOf(DetectorType.class);
        private Set<DetectorType> includes = EnumSet.noneOf(DetectorType.class);

        /**
         * Sets {@link AsyncTestConfig#threads}.
         * @param v the value to use
         * @return this builder
         */
        public Builder threads(int v)                        { threads = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#invocations}.
         * @param v the value to use
         * @return this builder
         */
        public Builder invocations(int v)                    { invocations = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#useVirtualThreads}.
         * @param v the value to use
         * @return this builder
         */
        public Builder useVirtualThreads(boolean v)          { useVirtualThreads = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#timeoutMs}.
         * @param v the value to use
         * @return this builder
         */
        public Builder timeoutMs(long v)                     { timeoutMs = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#virtualThreadStressMode}.
         * @param v the value to use
         * @return this builder
         */
        public Builder virtualThreadStressMode(String v)     { virtualThreadStressMode = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectAll}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectAll(boolean v)                  { detectAll = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#replaySeed}.
         * @param v the value to use
         * @return this builder
         */
        public Builder replaySeed(long v)                    { replaySeed = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#failOn}.
         * @param v the value to use
         * @return this builder
         */
        public Builder failOn(FailOn v)                      { failOn = (v != null) ? v : FailOn.NONE; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectDeadlocks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectDeadlocks(boolean v)            { detectDeadlocks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectVisibility}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVisibility(boolean v)           { detectVisibility = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectLivelocks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLivelocks(boolean v)            { detectLivelocks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectFalseSharing}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFalseSharing(boolean v)         { detectFalseSharing = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectWakeupIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectWakeupIssues(boolean v)         { detectWakeupIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#validateConstructorSafety}.
         * @param v the value to use
         * @return this builder
         */
        public Builder validateConstructorSafety(boolean v)  { validateConstructorSafety = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectABAProblem}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectABAProblem(boolean v)           { detectABAProblem = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#validateLockOrder}.
         * @param v the value to use
         * @return this builder
         */
        public Builder validateLockOrder(boolean v)          { validateLockOrder = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#monitorSynchronizers}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorSynchronizers(boolean v)       { monitorSynchronizers = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#monitorThreadPool}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorThreadPool(boolean v)          { monitorThreadPool = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectMemoryOrderingViolations}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectMemoryOrderingViolations(boolean v) { detectMemoryOrderingViolations = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#monitorAsyncPipeline}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorAsyncPipeline(boolean v)       { monitorAsyncPipeline = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#monitorReadWriteLockFairness}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorReadWriteLockFairness(boolean v) { monitorReadWriteLockFairness = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectRaceConditions}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectRaceConditions(boolean v)       { detectRaceConditions = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLocalLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLocalLeaks(boolean v)     { detectThreadLocalLeaks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectBusyWaiting}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectBusyWaiting(boolean v)          { detectBusyWaiting = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectAtomicityViolations}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectAtomicityViolations(boolean v)  { detectAtomicityViolations = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectInterruptMishandling}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectInterruptMishandling(boolean v) { detectInterruptMishandling = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#monitorSemaphore}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorSemaphore(boolean v)           { monitorSemaphore = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureExceptions}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureExceptions(boolean v) { detectCompletableFutureExceptions = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureCompletionLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureCompletionLeaks(boolean v) { detectCompletableFutureCompletionLeaks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadPinning}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadPinning(boolean v) { detectVirtualThreadPinning = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectThreadPoolDeadlocks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadPoolDeadlocks(boolean v) { detectThreadPoolDeadlocks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectConcurrentModifications}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConcurrentModifications(boolean v) { detectConcurrentModifications = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectLockLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLockLeaks(boolean v) { detectLockLeaks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedRandom}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedRandom(boolean v) { detectSharedRandom = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectBlockingQueueIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectBlockingQueueIssues(boolean v) { detectBlockingQueueIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectConditionVariableIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConditionVariableIssues(boolean v) { detectConditionVariableIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSimpleDateFormatIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSimpleDateFormatIssues(boolean v) { detectSimpleDateFormatIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectParallelStreamIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectParallelStreamIssues(boolean v) { detectParallelStreamIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectResourceLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectResourceLeaks(boolean v) { detectResourceLeaks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCountDownLatchIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCountDownLatchIssues(boolean v) { detectCountDownLatchIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCyclicBarrierIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCyclicBarrierIssues(boolean v) { detectCyclicBarrierIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectReentrantLockIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectReentrantLockIssues(boolean v) { detectReentrantLockIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectVolatileArrayIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVolatileArrayIssues(boolean v) { detectVolatileArrayIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectDoubleCheckedLocking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectDoubleCheckedLocking(boolean v) { detectDoubleCheckedLocking = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectWaitTimeout}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectWaitTimeout(boolean v) { detectWaitTimeout = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectLockContention}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLockContention(boolean v) { detectLockContention = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSynchronizedNonFinal}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSynchronizedNonFinal(boolean v) { detectSynchronizedNonFinal = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectMissedSignals}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectMissedSignals(boolean v) { detectMissedSignals = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectLazyInitRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLazyInitRace(boolean v) { detectLazyInitRace = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectPhaserIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectPhaserIssues(boolean v) { detectPhaserIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectStampedLockIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStampedLockIssues(boolean v) { detectStampedLockIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectExchangerIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectExchangerIssues(boolean v) { detectExchangerIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectScheduledExecutorIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectScheduledExecutorIssues(boolean v) { detectScheduledExecutorIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectForkJoinPoolIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectForkJoinPoolIssues(boolean v) { detectForkJoinPoolIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectThreadFactoryIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadFactoryIssues(boolean v) { detectThreadFactoryIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLeaks(boolean v) { detectThreadLeaks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSleepInLock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSleepInLock(boolean v) { detectSleepInLock = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectUnboundedQueue}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectUnboundedQueue(boolean v) { detectUnboundedQueue = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectThreadStarvation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadStarvation(boolean v) { detectThreadStarvation = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCalendarIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCalendarIssues(boolean v) { detectCalendarIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedCollections}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedCollections(boolean v) { detectSharedCollections = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectTimerIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectTimerIssues(boolean v) { detectTimerIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCopyOnWriteCollectionIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCopyOnWriteCollectionIssues(boolean v) { detectCopyOnWriteCollectionIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectStringBuilderIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStringBuilderIssues(boolean v)           { detectStringBuilderIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectStructuredConcurrencyIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStructuredConcurrencyIssues(boolean v)      { detectStructuredConcurrencyIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadContextLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadContextLeaks(boolean v)        { detectVirtualThreadContextLeaks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectScopedValueMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectScopedValueMisuse(boolean v)                { detectScopedValueMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadCpuBoundTasks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadCpuBoundTasks(boolean v)       { detectVirtualThreadCpuBoundTasks = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadCarrierExhaustion}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadCarrierExhaustion(boolean v)   { detectVirtualThreadCarrierExhaustion = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectHttpClientIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectHttpClientIssues(boolean v)                 { detectHttpClientIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectStreamClosing}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStreamClosing(boolean v)                  { detectStreamClosing = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCacheConcurrency}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCacheConcurrency(boolean v)               { detectCacheConcurrency = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureChainIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureChainIssues(boolean v)   { detectCompletableFutureChainIssues = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectExecutorShutdown}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectExecutorShutdown(boolean v)               { detectExecutorShutdown = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectMutableMapKeys}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectMutableMapKeys(boolean v)                 { detectMutableMapKeys = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectNestedMonitorLockout}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectNestedMonitorLockout(boolean v)           { detectNestedMonitorLockout = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectLockDowngrade}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLockDowngrade(boolean v)                  { detectLockDowngrade = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectInheritableThreadLocalMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectInheritableThreadLocalMisuse(boolean v)   { detectInheritableThreadLocalMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLocalContamination}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLocalContamination(boolean v)       { detectThreadLocalContamination = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectAtomicNonAtomicUpdates}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectAtomicNonAtomicUpdates(boolean v)         { detectAtomicNonAtomicUpdates = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSynchronizedCollectionIteration}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSynchronizedCollectionIteration(boolean v){ detectSynchronizedCollectionIteration = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedFormatter}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedFormatter(boolean v)                { detectSharedFormatter = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectConcurrentMapComputeRecursion}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConcurrentMapComputeRecursion(boolean v)  { detectConcurrentMapComputeRecursion = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSynchronizedOnLiteral}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSynchronizedOnLiteral(boolean v)          { detectSynchronizedOnLiteral = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectPublicLockExposure}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectPublicLockExposure(boolean v)             { detectPublicLockExposure = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectForkJoinTaskBlocking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectForkJoinTaskBlocking(boolean v)           { detectForkJoinTaskBlocking = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectOptimisticReadValidation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectOptimisticReadValidation(boolean v)       { detectOptimisticReadValidation = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCFCommonPoolBlocking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCFCommonPoolBlocking(boolean v)           { detectCFCommonPoolBlocking = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedMatcher}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedMatcher(boolean v)                  { detectSharedMatcher = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedDecimalFormat}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedDecimalFormat(boolean v)            { detectSharedDecimalFormat = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectWeakReferenceRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectWeakReferenceRace(boolean v)              { detectWeakReferenceRace = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectStatefulLambda}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStatefulLambda(boolean v)                 { detectStatefulLambda = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedMessageDigest}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedMessageDigest(boolean v)            { detectSharedMessageDigest = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectInterruptSwallowing}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectInterruptSwallowing(boolean v)            { detectInterruptSwallowing = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectMdcContextLeak}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectMdcContextLeak(boolean v)                 { detectMdcContextLeak = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSystemPropertyMutation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSystemPropertyMutation(boolean v)         { detectSystemPropertyMutation = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectFutureIgnored}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFutureIgnored(boolean v)                  { detectFutureIgnored = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectExplicitGc}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectExplicitGc(boolean v)                     { detectExplicitGc = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectDeprecatedThreadApi}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectDeprecatedThreadApi(boolean v)            { detectDeprecatedThreadApi = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedXmlParser}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedXmlParser(boolean v)                { detectSharedXmlParser = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectBoxedPrimitiveLock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectBoxedPrimitiveLock(boolean v)             { detectBoxedPrimitiveLock = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedTimeZone}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedTimeZone(boolean v)                 { detectSharedTimeZone = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectUncaughtExceptionHandler}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectUncaughtExceptionHandler(boolean v)       { detectUncaughtExceptionHandler = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectDaemonThreadHygiene}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectDaemonThreadHygiene(boolean v)            { detectDaemonThreadHygiene = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectNotifyWithoutMonitor}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectNotifyWithoutMonitor(boolean v)           { detectNotifyWithoutMonitor = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedSecureRandom}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedSecureRandom(boolean v)             { detectSharedSecureRandom = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectWeakHashMapShared}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectWeakHashMapShared(boolean v)              { detectWeakHashMapShared = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectJdbcConnectionShared}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectJdbcConnectionShared(boolean v)           { detectJdbcConnectionShared = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedStatefulCrypto}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedStatefulCrypto(boolean v)           { detectSharedStatefulCrypto = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectConcurrentMapCheckThenAct}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConcurrentMapCheckThenAct(boolean v)      { detectConcurrentMapCheckThenAct = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedDeflater}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedDeflater(boolean v)                 { detectSharedDeflater = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectThisEscape}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThisEscape(boolean v)                     { detectThisEscape = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLocalRandomMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLocalRandomMisuse(boolean v)        { detectThreadLocalRandomMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureObtrudeAbuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureObtrudeAbuse(boolean v)  { detectCompletableFutureObtrudeAbuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSpuriousWakeupHazard}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSpuriousWakeupHazard(boolean v)           { detectSpuriousWakeupHazard = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectLockUpgradeDeadlock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLockUpgradeDeadlock(boolean v)            { detectLockUpgradeDeadlock = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectTryLockMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectTryLockMisuse(boolean v)                  { detectTryLockMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectCFBlockingCallback}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCFBlockingCallback(boolean v)             { detectCFBlockingCallback = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectStableValueMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStableValueMisuse(boolean v)              { detectStableValueMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectStructuredTaskScopeMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStructuredTaskScopeMisuse(boolean v)      { detectStructuredTaskScopeMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectGathererConcurrencyMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectGathererConcurrencyMisuse(boolean v)      { detectGathererConcurrencyMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedByteBuffer}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedByteBuffer(boolean v)               { detectSharedByteBuffer = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedCharsetCoder}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedCharsetCoder(boolean v)             { detectSharedCharsetCoder = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedChecksum}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedChecksum(boolean v)                 { detectSharedChecksum = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectFileChannelPositionRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFileChannelPositionRace(boolean v)        { detectFileChannelPositionRace = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedIterator}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedIterator(boolean v)                 { detectSharedIterator = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectHighContentionAtomic}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectHighContentionAtomic(boolean v)           { detectHighContentionAtomic = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedJsonMapperReconfig}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedJsonMapperReconfig(boolean v)       { detectSharedJsonMapperReconfig = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectLazyConstantMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLazyConstantMisuse(boolean v)             { detectLazyConstantMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectFinalFieldMutation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFinalFieldMutation(boolean v)             { detectFinalFieldMutation = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedKdf}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedKdf(boolean v)                      { detectSharedKdf = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectLatchMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLatchMisuse(boolean v)                    { detectLatchMisuse = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectExecutorDeadlock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectExecutorDeadlock(boolean v)               { detectExecutorDeadlock = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectFutureBlocking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFutureBlocking(boolean v)                 { detectFutureBlocking = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectFlowPublisherConcurrency}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFlowPublisherConcurrency(boolean v)       { detectFlowPublisherConcurrency = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectConfinedArenaThreadEscape}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConfinedArenaThreadEscape(boolean v)      { detectConfinedArenaThreadEscape = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedMemorySegmentRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedMemorySegmentRace(boolean v)        { detectSharedMemorySegmentRace = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectVarHandleNonAtomicUpdate}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVarHandleNonAtomicUpdate(boolean v)       { detectVarHandleNonAtomicUpdate = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectRecordMutableComponentLeak}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectRecordMutableComponentLeak(boolean v)     { detectRecordMutableComponentLeak = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectStaticInitDeadlock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStaticInitDeadlock(boolean v)             { detectStaticInitDeadlock = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadPooling}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadPooling(boolean v)           { detectVirtualThreadPooling = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectPlatformThreadPerTask}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectPlatformThreadPerTask(boolean v)          { detectPlatformThreadPerTask = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectSharedSplittableRandom}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedSplittableRandom(boolean v)         { detectSharedSplittableRandom = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#enableBenchmarking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder enableBenchmarking(boolean v) { enableBenchmarking = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#benchmarkRegressionThreshold}.
         * @param v the value to use
         * @return this builder
         */
        public Builder benchmarkRegressionThreshold(double v) { benchmarkRegressionThreshold = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#failOnBenchmarkRegression}.
         * @param v the value to use
         * @return this builder
         */
        public Builder failOnBenchmarkRegression(boolean v) { failOnBenchmarkRegression = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#keygenAccountId}.
         * @param v the value to use
         * @return this builder
         */
        public Builder keygenAccountId(String v) { keygenAccountId = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#keygenApiKey}.
         * @param v the value to use
         * @return this builder
         */
        public Builder keygenApiKey(String v) { keygenApiKey = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#keygenProductId}.
         * @param v the value to use
         * @return this builder
         */
        public Builder keygenProductId(String v) { keygenProductId = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#lemonSqueezyStore}.
         * @param v the value to use
         * @return this builder
         */
        public Builder lemonSqueezyStore(String v) { lemonSqueezyStore = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#licenseKey}.
         * @param v the value to use
         * @return this builder
         */
        public Builder licenseKey(String v) { licenseKey = v; return this; }
        /**
         * Sets {@link AsyncTestConfig#licenseMockMode}.
         * @param v the value to use
         * @return this builder
         */
        public Builder licenseMockMode(boolean v) { licenseMockMode = v; return this; }

        /**
         * Sets {@link AsyncTest#excludes()}.
         * @param v the value to use
         * @return this builder
         */
        public Builder excludes(DetectorType[] v) {
            if (v != null && v.length > 0) {
                this.excludes.addAll(Arrays.asList(v));
            }
            return this;
        }

        /**
         * Enable exactly the listed detectors and nothing else. Mirrors
         * {@link AsyncTest#includes()}: when non-empty it overrides
         * {@link #detectAll(boolean)} and the per-detector setters;
         * {@link #excludes(DetectorType[])} still layers on top.
         *
         * @since 1.7.0
         *
         * @param v the detectors to enable exclusively; {@code null} or empty leaves the selection untouched
         * @return this builder
         */
        public Builder includes(DetectorType[] v) {
            if (v != null && v.length > 0) {
                this.includes.addAll(Arrays.asList(v));
            }
            return this;
        }

        /**
         * {@return the resolved configuration, with preset, includes and excludes applied}
         */
        public AsyncTestConfig build() {
            // Fail here, before any thread or barrier exists, so a bad shape names the
            // annotation attribute to fix. Without this bound, invocations <= 0 skipped the
            // runner's round loop entirely: the interceptor had already told JUnit the
            // invocation was handled, so the test reported green having run the body zero
            // times. threads <= 0 failed loudly, but only as new CyclicBarrier(0) deep
            // inside the first round.
            if (invocations < 1) {
                throw new IllegalArgumentException("invocations must be >= 1, was "
                        + invocations + " — 0 would report a passing test whose body never ran");
            }
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be >= 1, was " + threads);
            }
            if (!includes.isEmpty()) {
                // includes wins over detectAll/per-flag setters: force the
                // detectAll path and exclude everything outside the include set.
                // Explicit excludes are already in the set and thus still win.
                detectAll = true;
                for (DetectorType t : DetectorType.values()) {
                    if (!includes.contains(t)) {
                        excludes.add(t);
                    }
                }
            }
            // One resolution per detector, replacing the former detectAll/else pair.
            //
            //   detectAll  : enabled unless excluded          -> !excludes.contains(T)
            //   otherwise  : keep the explicit enable, then apply excludes
            //                                                 -> field && !excludes.contains(T)
            //
            // which is one expression: (detectAll || field) && !excludes.contains(T). Writing it
            // once per type instead of twice removes the failure this code has already had, where a
            // type present in one branch was missing from the other and silently ignored excludes.
            // A type can now only be forgotten entirely, which AsyncTestConfigBuildResolutionTest
            // fails on, rather than half-forgotten, which nothing noticed for several releases.
            detectDeadlocks = (detectAll || detectDeadlocks) && !excludes.contains(DetectorType.DEADLOCKS);
            detectVisibility = (detectAll || detectVisibility) && !excludes.contains(DetectorType.VISIBILITY);
            detectLivelocks = (detectAll || detectLivelocks) && !excludes.contains(DetectorType.LIVELOCKS);
            detectFalseSharing = (detectAll || detectFalseSharing) && !excludes.contains(DetectorType.FALSE_SHARING);
            detectWakeupIssues = (detectAll || detectWakeupIssues) && !excludes.contains(DetectorType.WAKEUP_ISSUES);
            validateConstructorSafety = (detectAll || validateConstructorSafety) && !excludes.contains(DetectorType.CONSTRUCTOR_SAFETY);
            detectABAProblem = (detectAll || detectABAProblem) && !excludes.contains(DetectorType.ABA_PROBLEM);
            validateLockOrder = (detectAll || validateLockOrder) && !excludes.contains(DetectorType.LOCK_ORDER);
            monitorSynchronizers = (detectAll || monitorSynchronizers) && !excludes.contains(DetectorType.SYNCHRONIZERS);
            monitorThreadPool = (detectAll || monitorThreadPool) && !excludes.contains(DetectorType.THREAD_POOL);
            detectMemoryOrderingViolations = (detectAll || detectMemoryOrderingViolations) && !excludes.contains(DetectorType.MEMORY_ORDERING);
            monitorAsyncPipeline = (detectAll || monitorAsyncPipeline) && !excludes.contains(DetectorType.ASYNC_PIPELINE);
            monitorReadWriteLockFairness = (detectAll || monitorReadWriteLockFairness) && !excludes.contains(DetectorType.READ_WRITE_LOCK_FAIRNESS);
            monitorSemaphore = (detectAll || monitorSemaphore) && !excludes.contains(DetectorType.SEMAPHORE);
            detectCompletableFutureExceptions = (detectAll || detectCompletableFutureExceptions) && !excludes.contains(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS);
            detectCompletableFutureCompletionLeaks = (detectAll || detectCompletableFutureCompletionLeaks) && !excludes.contains(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS);
            detectVirtualThreadPinning = (detectAll || detectVirtualThreadPinning) && !excludes.contains(DetectorType.VIRTUAL_THREAD_PINNING);
            detectThreadPoolDeadlocks = (detectAll || detectThreadPoolDeadlocks) && !excludes.contains(DetectorType.THREAD_POOL_DEADLOCK);
            detectConcurrentModifications = (detectAll || detectConcurrentModifications) && !excludes.contains(DetectorType.CONCURRENT_MODIFICATIONS);
            detectLockLeaks = (detectAll || detectLockLeaks) && !excludes.contains(DetectorType.LOCK_LEAKS);
            detectSharedRandom = (detectAll || detectSharedRandom) && !excludes.contains(DetectorType.SHARED_RANDOM);
            detectBlockingQueueIssues = (detectAll || detectBlockingQueueIssues) && !excludes.contains(DetectorType.BLOCKING_QUEUE);
            detectConditionVariableIssues = (detectAll || detectConditionVariableIssues) && !excludes.contains(DetectorType.CONDITION_VARIABLES);
            detectSimpleDateFormatIssues = (detectAll || detectSimpleDateFormatIssues) && !excludes.contains(DetectorType.SIMPLE_DATE_FORMAT);
            detectParallelStreamIssues = (detectAll || detectParallelStreamIssues) && !excludes.contains(DetectorType.PARALLEL_STREAMS);
            detectResourceLeaks = (detectAll || detectResourceLeaks) && !excludes.contains(DetectorType.RESOURCE_LEAKS);
            detectCountDownLatchIssues = (detectAll || detectCountDownLatchIssues) && !excludes.contains(DetectorType.COUNTDOWN_LATCH);
            detectCyclicBarrierIssues = (detectAll || detectCyclicBarrierIssues) && !excludes.contains(DetectorType.CYCLIC_BARRIER);
            detectReentrantLockIssues = (detectAll || detectReentrantLockIssues) && !excludes.contains(DetectorType.REENTRANT_LOCK);
            detectVolatileArrayIssues = (detectAll || detectVolatileArrayIssues) && !excludes.contains(DetectorType.VOLATILE_ARRAY);
            detectDoubleCheckedLocking = (detectAll || detectDoubleCheckedLocking) && !excludes.contains(DetectorType.DOUBLE_CHECKED_LOCKING);
            detectWaitTimeout = (detectAll || detectWaitTimeout) && !excludes.contains(DetectorType.WAIT_TIMEOUT);
            detectLockContention = (detectAll || detectLockContention) && !excludes.contains(DetectorType.LOCK_CONTENTION);
            detectSynchronizedNonFinal = (detectAll || detectSynchronizedNonFinal) && !excludes.contains(DetectorType.SYNCHRONIZED_NON_FINAL);
            detectMissedSignals = (detectAll || detectMissedSignals) && !excludes.contains(DetectorType.MISSED_SIGNAL);
            detectLazyInitRace = (detectAll || detectLazyInitRace) && !excludes.contains(DetectorType.LAZY_INIT_RACE);
            detectPhaserIssues = (detectAll || detectPhaserIssues) && !excludes.contains(DetectorType.PHASER);
            detectStampedLockIssues = (detectAll || detectStampedLockIssues) && !excludes.contains(DetectorType.STAMPED_LOCK);
            detectExchangerIssues = (detectAll || detectExchangerIssues) && !excludes.contains(DetectorType.EXCHANGER);
            detectScheduledExecutorIssues = (detectAll || detectScheduledExecutorIssues) && !excludes.contains(DetectorType.SCHEDULED_EXECUTOR);
            detectForkJoinPoolIssues = (detectAll || detectForkJoinPoolIssues) && !excludes.contains(DetectorType.FORK_JOIN_POOL);
            detectThreadFactoryIssues = (detectAll || detectThreadFactoryIssues) && !excludes.contains(DetectorType.THREAD_FACTORY);
            detectThreadLeaks = (detectAll || detectThreadLeaks) && !excludes.contains(DetectorType.THREAD_LEAKS);
            detectSleepInLock = (detectAll || detectSleepInLock) && !excludes.contains(DetectorType.SLEEP_IN_LOCK);
            detectUnboundedQueue = (detectAll || detectUnboundedQueue) && !excludes.contains(DetectorType.UNBOUNDED_QUEUE);
            detectThreadStarvation = (detectAll || detectThreadStarvation) && !excludes.contains(DetectorType.THREAD_STARVATION);
            detectCalendarIssues = (detectAll || detectCalendarIssues) && !excludes.contains(DetectorType.CALENDAR);
            detectSharedCollections = (detectAll || detectSharedCollections) && !excludes.contains(DetectorType.SHARED_COLLECTIONS);
            detectTimerIssues = (detectAll || detectTimerIssues) && !excludes.contains(DetectorType.TIMER);
            detectCopyOnWriteCollectionIssues = (detectAll || detectCopyOnWriteCollectionIssues) && !excludes.contains(DetectorType.COPY_ON_WRITE_COLLECTIONS);
            detectStringBuilderIssues = (detectAll || detectStringBuilderIssues) && !excludes.contains(DetectorType.STRING_BUILDER);
            detectStructuredConcurrencyIssues = (detectAll || detectStructuredConcurrencyIssues) && !excludes.contains(DetectorType.STRUCTURED_CONCURRENCY);
            detectVirtualThreadContextLeaks = (detectAll || detectVirtualThreadContextLeaks) && !excludes.contains(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS);
            detectScopedValueMisuse = (detectAll || detectScopedValueMisuse) && !excludes.contains(DetectorType.SCOPED_VALUE);
            detectVirtualThreadCpuBoundTasks = (detectAll || detectVirtualThreadCpuBoundTasks) && !excludes.contains(DetectorType.VIRTUAL_THREAD_CPU_BOUND);
            detectVirtualThreadCarrierExhaustion = (detectAll || detectVirtualThreadCarrierExhaustion) && !excludes.contains(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION);
            detectRaceConditions = (detectAll || detectRaceConditions) && !excludes.contains(DetectorType.RACE_CONDITIONS);
            detectThreadLocalLeaks = (detectAll || detectThreadLocalLeaks) && !excludes.contains(DetectorType.THREAD_LOCAL_LEAKS);
            detectBusyWaiting = (detectAll || detectBusyWaiting) && !excludes.contains(DetectorType.BUSY_WAITING);
            detectAtomicityViolations = (detectAll || detectAtomicityViolations) && !excludes.contains(DetectorType.ATOMICITY_VIOLATIONS);
            detectInterruptMishandling = (detectAll || detectInterruptMishandling) && !excludes.contains(DetectorType.INTERRUPT_MISHANDLING);
            detectHttpClientIssues = (detectAll || detectHttpClientIssues) && !excludes.contains(DetectorType.HTTP_CLIENT);
            detectStreamClosing = (detectAll || detectStreamClosing) && !excludes.contains(DetectorType.STREAM_CLOSING);
            detectCacheConcurrency = (detectAll || detectCacheConcurrency) && !excludes.contains(DetectorType.CACHE_CONCURRENCY);
            detectCompletableFutureChainIssues = (detectAll || detectCompletableFutureChainIssues) && !excludes.contains(DetectorType.COMPLETABLEFUTURE_CHAIN);
            detectExecutorShutdown = (detectAll || detectExecutorShutdown) && !excludes.contains(DetectorType.EXECUTOR_SHUTDOWN);
            detectMutableMapKeys = (detectAll || detectMutableMapKeys) && !excludes.contains(DetectorType.MUTABLE_MAP_KEY);
            detectNestedMonitorLockout = (detectAll || detectNestedMonitorLockout) && !excludes.contains(DetectorType.NESTED_MONITOR_LOCKOUT);
            detectLockDowngrade = (detectAll || detectLockDowngrade) && !excludes.contains(DetectorType.LOCK_DOWNGRADE);
            detectInheritableThreadLocalMisuse = (detectAll || detectInheritableThreadLocalMisuse) && !excludes.contains(DetectorType.INHERITABLE_THREAD_LOCAL);
            detectThreadLocalContamination = (detectAll || detectThreadLocalContamination) && !excludes.contains(DetectorType.THREAD_LOCAL_CONTAMINATION);
            detectAtomicNonAtomicUpdates = (detectAll || detectAtomicNonAtomicUpdates) && !excludes.contains(DetectorType.ATOMIC_NON_ATOMIC_UPDATE);
            detectSynchronizedCollectionIteration = (detectAll || detectSynchronizedCollectionIteration) && !excludes.contains(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION);
            detectSharedFormatter = (detectAll || detectSharedFormatter) && !excludes.contains(DetectorType.SHARED_FORMATTER);
            detectConcurrentMapComputeRecursion = (detectAll || detectConcurrentMapComputeRecursion) && !excludes.contains(DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION);
            detectSynchronizedOnLiteral = (detectAll || detectSynchronizedOnLiteral) && !excludes.contains(DetectorType.SYNCHRONIZED_ON_LITERAL);
            detectPublicLockExposure = (detectAll || detectPublicLockExposure) && !excludes.contains(DetectorType.PUBLIC_LOCK_EXPOSURE);
            detectForkJoinTaskBlocking = (detectAll || detectForkJoinTaskBlocking) && !excludes.contains(DetectorType.FORK_JOIN_TASK_BLOCKING);
            detectOptimisticReadValidation = (detectAll || detectOptimisticReadValidation) && !excludes.contains(DetectorType.OPTIMISTIC_READ_VALIDATION);
            detectCFCommonPoolBlocking = (detectAll || detectCFCommonPoolBlocking) && !excludes.contains(DetectorType.CF_COMMON_POOL_BLOCKING);
            detectSharedMatcher = (detectAll || detectSharedMatcher) && !excludes.contains(DetectorType.SHARED_MATCHER);
            detectSharedDecimalFormat = (detectAll || detectSharedDecimalFormat) && !excludes.contains(DetectorType.SHARED_DECIMAL_FORMAT);
            detectWeakReferenceRace = (detectAll || detectWeakReferenceRace) && !excludes.contains(DetectorType.WEAK_REFERENCE_RACE);
            detectStatefulLambda = (detectAll || detectStatefulLambda) && !excludes.contains(DetectorType.STATEFUL_LAMBDA);
            detectSharedMessageDigest = (detectAll || detectSharedMessageDigest) && !excludes.contains(DetectorType.SHARED_MESSAGE_DIGEST);
            detectInterruptSwallowing = (detectAll || detectInterruptSwallowing) && !excludes.contains(DetectorType.INTERRUPT_SWALLOWING);
            detectMdcContextLeak = (detectAll || detectMdcContextLeak) && !excludes.contains(DetectorType.MDC_CONTEXT_LEAK);
            detectSystemPropertyMutation = (detectAll || detectSystemPropertyMutation) && !excludes.contains(DetectorType.SYSTEM_PROPERTY_MUTATION);
            detectFutureIgnored = (detectAll || detectFutureIgnored) && !excludes.contains(DetectorType.FUTURE_IGNORED);
            detectExplicitGc = (detectAll || detectExplicitGc) && !excludes.contains(DetectorType.EXPLICIT_GC);
            detectDeprecatedThreadApi = (detectAll || detectDeprecatedThreadApi) && !excludes.contains(DetectorType.DEPRECATED_THREAD_API);
            detectSharedXmlParser = (detectAll || detectSharedXmlParser) && !excludes.contains(DetectorType.SHARED_XML_PARSER);
            detectBoxedPrimitiveLock = (detectAll || detectBoxedPrimitiveLock) && !excludes.contains(DetectorType.BOXED_PRIMITIVE_LOCK);
            detectSharedTimeZone = (detectAll || detectSharedTimeZone) && !excludes.contains(DetectorType.SHARED_TIMEZONE);
            detectUncaughtExceptionHandler = (detectAll || detectUncaughtExceptionHandler) && !excludes.contains(DetectorType.UNCAUGHT_EXCEPTION_HANDLER);
            detectDaemonThreadHygiene = (detectAll || detectDaemonThreadHygiene) && !excludes.contains(DetectorType.DAEMON_THREAD_HYGIENE);
            detectNotifyWithoutMonitor = (detectAll || detectNotifyWithoutMonitor) && !excludes.contains(DetectorType.NOTIFY_WITHOUT_MONITOR);
            detectSharedSecureRandom = (detectAll || detectSharedSecureRandom) && !excludes.contains(DetectorType.SHARED_SECURE_RANDOM);
            detectWeakHashMapShared = (detectAll || detectWeakHashMapShared) && !excludes.contains(DetectorType.WEAK_HASH_MAP_SHARED);
            detectJdbcConnectionShared = (detectAll || detectJdbcConnectionShared) && !excludes.contains(DetectorType.JDBC_CONNECTION_SHARED);
            detectSharedStatefulCrypto = (detectAll || detectSharedStatefulCrypto) && !excludes.contains(DetectorType.SHARED_STATEFUL_CRYPTO);
            detectConcurrentMapCheckThenAct = (detectAll || detectConcurrentMapCheckThenAct) && !excludes.contains(DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT);
            detectSharedDeflater = (detectAll || detectSharedDeflater) && !excludes.contains(DetectorType.SHARED_DEFLATER);
            detectThisEscape = (detectAll || detectThisEscape) && !excludes.contains(DetectorType.THIS_ESCAPE);
            detectThreadLocalRandomMisuse = (detectAll || detectThreadLocalRandomMisuse) && !excludes.contains(DetectorType.THREAD_LOCAL_RANDOM_MISUSE);
            detectCompletableFutureObtrudeAbuse = (detectAll || detectCompletableFutureObtrudeAbuse) && !excludes.contains(DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE);
            detectSpuriousWakeupHazard = (detectAll || detectSpuriousWakeupHazard) && !excludes.contains(DetectorType.SPURIOUS_WAKEUP_HAZARD);
            detectLockUpgradeDeadlock = (detectAll || detectLockUpgradeDeadlock) && !excludes.contains(DetectorType.LOCK_UPGRADE_DEADLOCK);
            detectTryLockMisuse = (detectAll || detectTryLockMisuse) && !excludes.contains(DetectorType.TRY_LOCK_MISUSE);
            detectCFBlockingCallback = (detectAll || detectCFBlockingCallback) && !excludes.contains(DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK);
            detectStableValueMisuse = (detectAll || detectStableValueMisuse) && !excludes.contains(DetectorType.STABLE_VALUE_MISUSE);
            detectStructuredTaskScopeMisuse = (detectAll || detectStructuredTaskScopeMisuse) && !excludes.contains(DetectorType.STRUCTURED_TASK_SCOPE_MISUSE);
            detectGathererConcurrencyMisuse = (detectAll || detectGathererConcurrencyMisuse) && !excludes.contains(DetectorType.GATHERER_CONCURRENCY_MISUSE);
            detectSharedByteBuffer = (detectAll || detectSharedByteBuffer) && !excludes.contains(DetectorType.SHARED_BYTE_BUFFER);
            detectSharedCharsetCoder = (detectAll || detectSharedCharsetCoder) && !excludes.contains(DetectorType.SHARED_CHARSET_CODER);
            detectSharedChecksum = (detectAll || detectSharedChecksum) && !excludes.contains(DetectorType.SHARED_CHECKSUM);
            detectFileChannelPositionRace = (detectAll || detectFileChannelPositionRace) && !excludes.contains(DetectorType.FILE_CHANNEL_POSITION_RACE);
            detectSharedIterator = (detectAll || detectSharedIterator) && !excludes.contains(DetectorType.SHARED_ITERATOR);
            detectHighContentionAtomic = (detectAll || detectHighContentionAtomic) && !excludes.contains(DetectorType.HIGH_CONTENTION_ATOMIC);
            detectSharedJsonMapperReconfig = (detectAll || detectSharedJsonMapperReconfig) && !excludes.contains(DetectorType.SHARED_JSON_MAPPER_RECONFIG);
            detectLazyConstantMisuse = (detectAll || detectLazyConstantMisuse) && !excludes.contains(DetectorType.LAZY_CONSTANT_MISUSE);
            detectFinalFieldMutation = (detectAll || detectFinalFieldMutation) && !excludes.contains(DetectorType.FINAL_FIELD_MUTATION);
            detectSharedKdf = (detectAll || detectSharedKdf) && !excludes.contains(DetectorType.SHARED_KDF);
            detectLatchMisuse = (detectAll || detectLatchMisuse) && !excludes.contains(DetectorType.LATCH_MISUSE);
            detectExecutorDeadlock = (detectAll || detectExecutorDeadlock) && !excludes.contains(DetectorType.EXECUTOR_DEADLOCK);
            detectFutureBlocking = (detectAll || detectFutureBlocking) && !excludes.contains(DetectorType.FUTURE_BLOCKING);
            detectFlowPublisherConcurrency = (detectAll || detectFlowPublisherConcurrency) && !excludes.contains(DetectorType.FLOW_PUBLISHER_CONCURRENCY);
            detectConfinedArenaThreadEscape = (detectAll || detectConfinedArenaThreadEscape) && !excludes.contains(DetectorType.CONFINED_ARENA_THREAD_ESCAPE);
            detectSharedMemorySegmentRace = (detectAll || detectSharedMemorySegmentRace) && !excludes.contains(DetectorType.SHARED_MEMORY_SEGMENT_RACE);
            detectVarHandleNonAtomicUpdate = (detectAll || detectVarHandleNonAtomicUpdate) && !excludes.contains(DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE);
            detectRecordMutableComponentLeak = (detectAll || detectRecordMutableComponentLeak) && !excludes.contains(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK);
            detectStaticInitDeadlock = (detectAll || detectStaticInitDeadlock) && !excludes.contains(DetectorType.STATIC_INIT_DEADLOCK);
            detectVirtualThreadPooling = (detectAll || detectVirtualThreadPooling) && !excludes.contains(DetectorType.VIRTUAL_THREAD_POOLING);
            detectPlatformThreadPerTask = (detectAll || detectPlatformThreadPerTask) && !excludes.contains(DetectorType.PLATFORM_THREAD_PER_TASK);
            detectSharedSplittableRandom = (detectAll || detectSharedSplittableRandom) && !excludes.contains(DetectorType.SHARED_SPLITTABLE_RANDOM);
            return new AsyncTestConfig(this);
        }
    }
}
