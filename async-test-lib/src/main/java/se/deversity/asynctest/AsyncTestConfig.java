package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIImmutable;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable snapshot of all {@link AsyncTest} parameters.
 * Passed to {@link se.deversity.asynctest.runner.ConcurrencyRunner} as a single object
 * instead of an ever-growing parameter list.
 */
@AICore(
    sensitivity = "Critical",
    note = "Adding a new detector requires synchronized changes across six places: @AsyncTest attribute, AsyncTestConfig field, Builder default, from(AsyncTest) call chain, build() detectAll/excludes blocks, and DetectorRegistry constructor."
)
@AIContext(
    focus = "Maintain strict 1:1 mapping between @AsyncTest attributes, Builder fields, from(AsyncTest), build() logic, and DetectorRegistry",
    avoids = "mutable state — this class must remain immutable after construction"
)
@AIImmutable(note = "Immutable snapshot of @AsyncTest parameters to ensure thread safety.")
@API(status = Status.STABLE)
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
    public final boolean detectLockContention;
    public final boolean detectSynchronizedNonFinal;
    public final boolean detectMissedSignals;
    public final boolean detectLazyInitRace;

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
    public final boolean detectVirtualThreadCpuBoundTasks;
    public final boolean detectVirtualThreadCarrierExhaustion;

    // ---- Phase 7: High-Level Concurrency Patterns ----
    public final boolean detectHttpClientIssues;
    public final boolean detectStreamClosing;
    public final boolean detectCacheConcurrency;
    public final boolean detectCompletableFutureChainIssues;

    // ---- Phase 8: Lifecycle & Structural Correctness ----
    public final boolean detectExecutorShutdown;
    public final boolean detectMutableMapKeys;
    public final boolean detectNestedMonitorLockout;
    public final boolean detectLockDowngrade;
    public final boolean detectInheritableThreadLocalMisuse;
    public final boolean detectUncommittedChanges;

    // ---- Phase 10: API Traps & Subtle Concurrency Bugs ----
    public final boolean detectThreadLocalContamination;
    public final boolean detectAtomicNonAtomicUpdates;
    public final boolean detectSynchronizedCollectionIteration;
    public final boolean detectSharedFormatter;
    public final boolean detectConcurrentMapComputeRecursion;
    public final boolean detectSynchronizedOnLiteral;
    public final boolean detectPublicLockExposure;
    public final boolean detectForkJoinTaskBlocking;
    public final boolean detectOptimisticReadValidation;
    public final boolean detectCFCommonPoolBlocking;

    // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----
    public final boolean detectSharedMatcher;
    public final boolean detectSharedDecimalFormat;
    public final boolean detectWeakReferenceRace;
    public final boolean detectStatefulLambda;
    public final boolean detectSharedMessageDigest;

    // ---- Phase 12: Operational & Hygiene Concurrency Issues ----
    public final boolean detectInterruptSwallowing;
    public final boolean detectMdcContextLeak;
    public final boolean detectSystemPropertyMutation;
    public final boolean detectFutureIgnored;
    public final boolean detectExplicitGc;
    public final boolean detectDeprecatedThreadApi;
    public final boolean detectSharedXmlParser;
    public final boolean detectBoxedPrimitiveLock;
    public final boolean detectSharedTimeZone;
    public final boolean detectUncaughtExceptionHandler;

    // ---- Phase 13 (1.0.0+) ----
    public final boolean detectDaemonThreadHygiene;
    public final boolean detectNotifyWithoutMonitor;
    public final boolean detectSharedSecureRandom;
    public final boolean detectWeakHashMapShared;
    public final boolean detectJdbcConnectionShared;

    // ---- Phase 14 (1.7.0+) ----
    public final boolean detectSharedStatefulCrypto;
    public final boolean detectConcurrentMapCheckThenAct;
    public final boolean detectSharedDeflater;
    public final boolean detectThisEscape;
    public final boolean detectThreadLocalRandomMisuse;

    // ---- Phase 15 (1.8.0+) ----
    public final boolean detectCompletableFutureObtrudeAbuse;
    public final boolean detectSpuriousWakeupHazard;
    public final boolean detectLockUpgradeDeadlock;
    public final boolean detectTryLockMisuse;
    public final boolean detectCFBlockingCallback;

    // ---- Phase 16: JDK 25/26 preview-era detectors ----
    public final boolean detectStableValueMisuse;
    public final boolean detectStructuredTaskScopeMisuse;
    public final boolean detectGathererConcurrencyMisuse;

    // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----
    public final boolean detectSharedByteBuffer;
    public final boolean detectSharedCharsetCoder;
    public final boolean detectSharedChecksum;
    public final boolean detectFileChannelPositionRace;
    public final boolean detectSharedIterator;
    public final boolean detectHighContentionAtomic;
    public final boolean detectSharedJsonMapperReconfig;

    // ---- Phase 18: JDK 25/26 GA-era concurrency detectors ----
    public final boolean detectLazyConstantMisuse;
    public final boolean detectFinalFieldMutation;
    public final boolean detectSharedKdf;
    public final boolean detectLatchMisuse;
    public final boolean detectExecutorDeadlock;
    public final boolean detectFutureBlocking;

    // ---- Benchmarking ----
    @AIFeatureFlag(flag = "async-test.benchmarking.enabled", defaultValue = false)
    public final boolean enableBenchmarking;
    public final double benchmarkRegressionThreshold;
    public final boolean failOnBenchmarkRegression;

    // ---- License Gating ----
    public final String keygenAccountId;
    public final String keygenApiKey;
    public final String keygenProductId;
    public final String lemonSqueezyStore;
    public final String licenseKey;
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
        detectUncommittedChanges           = b.detectUncommittedChanges;
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

    /** Builds a config from an {@link AsyncTest} annotation instance. */
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
            Set<DetectorType> enabled = preset.enabled();
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
            .detectUncommittedChanges(ann.detectUncommittedChanges())
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
        private boolean detectUncommittedChanges           = false;
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

        public Builder threads(int v)                        { threads = v; return this; }
        public Builder invocations(int v)                    { invocations = v; return this; }
        public Builder useVirtualThreads(boolean v)          { useVirtualThreads = v; return this; }
        public Builder timeoutMs(long v)                     { timeoutMs = v; return this; }
        public Builder virtualThreadStressMode(String v)     { virtualThreadStressMode = v; return this; }
        public Builder detectAll(boolean v)                  { detectAll = v; return this; }
        public Builder replaySeed(long v)                    { replaySeed = v; return this; }
        public Builder failOn(FailOn v)                      { failOn = (v != null) ? v : FailOn.NONE; return this; }
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
        public Builder detectLockContention(boolean v) { detectLockContention = v; return this; }
        public Builder detectSynchronizedNonFinal(boolean v) { detectSynchronizedNonFinal = v; return this; }
        public Builder detectMissedSignals(boolean v) { detectMissedSignals = v; return this; }
        public Builder detectLazyInitRace(boolean v) { detectLazyInitRace = v; return this; }
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
        public Builder detectStructuredConcurrencyIssues(boolean v)      { detectStructuredConcurrencyIssues = v; return this; }
        public Builder detectVirtualThreadContextLeaks(boolean v)        { detectVirtualThreadContextLeaks = v; return this; }
        public Builder detectScopedValueMisuse(boolean v)                { detectScopedValueMisuse = v; return this; }
        public Builder detectVirtualThreadCpuBoundTasks(boolean v)       { detectVirtualThreadCpuBoundTasks = v; return this; }
        public Builder detectVirtualThreadCarrierExhaustion(boolean v)   { detectVirtualThreadCarrierExhaustion = v; return this; }
        public Builder detectHttpClientIssues(boolean v)                 { detectHttpClientIssues = v; return this; }
        public Builder detectStreamClosing(boolean v)                  { detectStreamClosing = v; return this; }
        public Builder detectCacheConcurrency(boolean v)               { detectCacheConcurrency = v; return this; }
        public Builder detectCompletableFutureChainIssues(boolean v)   { detectCompletableFutureChainIssues = v; return this; }
        public Builder detectExecutorShutdown(boolean v)               { detectExecutorShutdown = v; return this; }
        public Builder detectMutableMapKeys(boolean v)                 { detectMutableMapKeys = v; return this; }
        public Builder detectNestedMonitorLockout(boolean v)           { detectNestedMonitorLockout = v; return this; }
        public Builder detectLockDowngrade(boolean v)                  { detectLockDowngrade = v; return this; }
        public Builder detectInheritableThreadLocalMisuse(boolean v)   { detectInheritableThreadLocalMisuse = v; return this; }
        public Builder detectUncommittedChanges(boolean v)             { detectUncommittedChanges = v; return this; }
        public Builder detectThreadLocalContamination(boolean v)       { detectThreadLocalContamination = v; return this; }
        public Builder detectAtomicNonAtomicUpdates(boolean v)         { detectAtomicNonAtomicUpdates = v; return this; }
        public Builder detectSynchronizedCollectionIteration(boolean v){ detectSynchronizedCollectionIteration = v; return this; }
        public Builder detectSharedFormatter(boolean v)                { detectSharedFormatter = v; return this; }
        public Builder detectConcurrentMapComputeRecursion(boolean v)  { detectConcurrentMapComputeRecursion = v; return this; }
        public Builder detectSynchronizedOnLiteral(boolean v)          { detectSynchronizedOnLiteral = v; return this; }
        public Builder detectPublicLockExposure(boolean v)             { detectPublicLockExposure = v; return this; }
        public Builder detectForkJoinTaskBlocking(boolean v)           { detectForkJoinTaskBlocking = v; return this; }
        public Builder detectOptimisticReadValidation(boolean v)       { detectOptimisticReadValidation = v; return this; }
        public Builder detectCFCommonPoolBlocking(boolean v)           { detectCFCommonPoolBlocking = v; return this; }
        public Builder detectSharedMatcher(boolean v)                  { detectSharedMatcher = v; return this; }
        public Builder detectSharedDecimalFormat(boolean v)            { detectSharedDecimalFormat = v; return this; }
        public Builder detectWeakReferenceRace(boolean v)              { detectWeakReferenceRace = v; return this; }
        public Builder detectStatefulLambda(boolean v)                 { detectStatefulLambda = v; return this; }
        public Builder detectSharedMessageDigest(boolean v)            { detectSharedMessageDigest = v; return this; }
        public Builder detectInterruptSwallowing(boolean v)            { detectInterruptSwallowing = v; return this; }
        public Builder detectMdcContextLeak(boolean v)                 { detectMdcContextLeak = v; return this; }
        public Builder detectSystemPropertyMutation(boolean v)         { detectSystemPropertyMutation = v; return this; }
        public Builder detectFutureIgnored(boolean v)                  { detectFutureIgnored = v; return this; }
        public Builder detectExplicitGc(boolean v)                     { detectExplicitGc = v; return this; }
        public Builder detectDeprecatedThreadApi(boolean v)            { detectDeprecatedThreadApi = v; return this; }
        public Builder detectSharedXmlParser(boolean v)                { detectSharedXmlParser = v; return this; }
        public Builder detectBoxedPrimitiveLock(boolean v)             { detectBoxedPrimitiveLock = v; return this; }
        public Builder detectSharedTimeZone(boolean v)                 { detectSharedTimeZone = v; return this; }
        public Builder detectUncaughtExceptionHandler(boolean v)       { detectUncaughtExceptionHandler = v; return this; }
        public Builder detectDaemonThreadHygiene(boolean v)            { detectDaemonThreadHygiene = v; return this; }
        public Builder detectNotifyWithoutMonitor(boolean v)           { detectNotifyWithoutMonitor = v; return this; }
        public Builder detectSharedSecureRandom(boolean v)             { detectSharedSecureRandom = v; return this; }
        public Builder detectWeakHashMapShared(boolean v)              { detectWeakHashMapShared = v; return this; }
        public Builder detectJdbcConnectionShared(boolean v)           { detectJdbcConnectionShared = v; return this; }
        public Builder detectSharedStatefulCrypto(boolean v)           { detectSharedStatefulCrypto = v; return this; }
        public Builder detectConcurrentMapCheckThenAct(boolean v)      { detectConcurrentMapCheckThenAct = v; return this; }
        public Builder detectSharedDeflater(boolean v)                 { detectSharedDeflater = v; return this; }
        public Builder detectThisEscape(boolean v)                     { detectThisEscape = v; return this; }
        public Builder detectThreadLocalRandomMisuse(boolean v)        { detectThreadLocalRandomMisuse = v; return this; }
        public Builder detectCompletableFutureObtrudeAbuse(boolean v)  { detectCompletableFutureObtrudeAbuse = v; return this; }
        public Builder detectSpuriousWakeupHazard(boolean v)           { detectSpuriousWakeupHazard = v; return this; }
        public Builder detectLockUpgradeDeadlock(boolean v)            { detectLockUpgradeDeadlock = v; return this; }
        public Builder detectTryLockMisuse(boolean v)                  { detectTryLockMisuse = v; return this; }
        public Builder detectCFBlockingCallback(boolean v)             { detectCFBlockingCallback = v; return this; }
        public Builder detectStableValueMisuse(boolean v)              { detectStableValueMisuse = v; return this; }
        public Builder detectStructuredTaskScopeMisuse(boolean v)      { detectStructuredTaskScopeMisuse = v; return this; }
        public Builder detectGathererConcurrencyMisuse(boolean v)      { detectGathererConcurrencyMisuse = v; return this; }
        public Builder detectSharedByteBuffer(boolean v)               { detectSharedByteBuffer = v; return this; }
        public Builder detectSharedCharsetCoder(boolean v)             { detectSharedCharsetCoder = v; return this; }
        public Builder detectSharedChecksum(boolean v)                 { detectSharedChecksum = v; return this; }
        public Builder detectFileChannelPositionRace(boolean v)        { detectFileChannelPositionRace = v; return this; }
        public Builder detectSharedIterator(boolean v)                 { detectSharedIterator = v; return this; }
        public Builder detectHighContentionAtomic(boolean v)           { detectHighContentionAtomic = v; return this; }
        public Builder detectSharedJsonMapperReconfig(boolean v)       { detectSharedJsonMapperReconfig = v; return this; }
        public Builder detectLazyConstantMisuse(boolean v)             { detectLazyConstantMisuse = v; return this; }
        public Builder detectFinalFieldMutation(boolean v)             { detectFinalFieldMutation = v; return this; }
        public Builder detectSharedKdf(boolean v)                      { detectSharedKdf = v; return this; }
        public Builder detectLatchMisuse(boolean v)                    { detectLatchMisuse = v; return this; }
        public Builder detectExecutorDeadlock(boolean v)               { detectExecutorDeadlock = v; return this; }
        public Builder detectFutureBlocking(boolean v)                 { detectFutureBlocking = v; return this; }
        public Builder enableBenchmarking(boolean v) { enableBenchmarking = v; return this; }
        public Builder benchmarkRegressionThreshold(double v) { benchmarkRegressionThreshold = v; return this; }
        public Builder failOnBenchmarkRegression(boolean v) { failOnBenchmarkRegression = v; return this; }
        public Builder keygenAccountId(String v) { keygenAccountId = v; return this; }
        public Builder keygenApiKey(String v) { keygenApiKey = v; return this; }
        public Builder keygenProductId(String v) { keygenProductId = v; return this; }
        public Builder lemonSqueezyStore(String v) { lemonSqueezyStore = v; return this; }
        public Builder licenseKey(String v) { licenseKey = v; return this; }
        public Builder licenseMockMode(boolean v) { licenseMockMode = v; return this; }

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
         */
        public Builder includes(DetectorType[] v) {
            if (v != null && v.length > 0) {
                this.includes.addAll(Arrays.asList(v));
            }
            return this;
        }

        public AsyncTestConfig build() {
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
            detectUncommittedChanges = (detectAll || detectUncommittedChanges) && !excludes.contains(DetectorType.UNCOMMITTED_CHANGES);
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
            return new AsyncTestConfig(this);
        }
    }
}
