package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIImmutable;

import java.util.Arrays;
import java.util.Collections;
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
    note = "Selection is one EnumSet resolved once in build() (#917); every public detector flag is assigned enabled.contains(TYPE) in the constructor and nowhere else, so a flag cannot disagree with enabledDetectors(). A new detector here is the flag and its derivation and the Builder setter that calls flag(TYPE, v); @AsyncTest has no per-detector attribute to read since 2.0.0 (#920). Never reintroduce a per-detector resolution expression in build()."
)
@AIContext(
    focus = "Keep one public flag per DetectorType, each derived from the enabled set, and the selection in from(AsyncTest) and build() expressed only through includes, excludes, preset and detectAll",
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

    /**
     * Lowest detector trust tier whose findings {@link #failOn} may act on.
     *
     * <p>{@link TrustTier#ADVISORY} (default) is the weakest tier, so it filters nothing and the
     * gate behaves as it did before this field existed. Findings below the floor are still
     * reported; they just cannot fail the test.
     * @since 1.9.7
     */
    public final TrustTier minTrust;

    /**
     * The detectors this run enables, resolved once in {@link Builder#build()} from
     * {@code detectAll}, the per-detector switches, {@code includes} and {@code excludes}. Every
     * public detector flag below is a membership test against this set, so none can disagree
     * with it (#917).
     */
    private final Set<DetectorType> enabledDetectors;

    /** The detector ids the test switched off; see {@link #isEnabled(String)}. */
    private final Set<String> excludedIds;

    // ---- Phase 1 ----
    /** Whether {@link DetectorType#DEADLOCKS} is enabled for this run. */
    public final boolean detectDeadlocks;
    /** Whether {@link DetectorType#VISIBILITY} is enabled for this run. */
    public final boolean detectVisibility;
    /** Whether {@link DetectorType#LIVELOCKS} is enabled for this run. */
    public final boolean detectLivelocks;

    // ---- Phase 2 ----
    /** Whether {@link DetectorType#FALSE_SHARING} is enabled for this run. */
    public final boolean detectFalseSharing;
    /** Whether {@link DetectorType#WAKEUP_ISSUES} is enabled for this run. */
    public final boolean detectWakeupIssues;
    /** Whether {@link DetectorType#CONSTRUCTOR_SAFETY} is enabled for this run. */
    public final boolean validateConstructorSafety;
    /** Whether {@link DetectorType#ABA_PROBLEM} is enabled for this run. */
    public final boolean detectABAProblem;
    /** Whether {@link DetectorType#LOCK_ORDER} is enabled for this run. */
    public final boolean validateLockOrder;
    /** Whether {@link DetectorType#SYNCHRONIZERS} is enabled for this run. */
    public final boolean monitorSynchronizers;
    /** Whether {@link DetectorType#THREAD_POOL} is enabled for this run. */
    public final boolean monitorThreadPool;
    /** Whether {@link DetectorType#MEMORY_ORDERING} is enabled for this run. */
    public final boolean detectMemoryOrderingViolations;
    /** Whether {@link DetectorType#ASYNC_PIPELINE} is enabled for this run. */
    public final boolean monitorAsyncPipeline;
    /** Whether {@link DetectorType#READ_WRITE_LOCK_FAIRNESS} is enabled for this run. */
    public final boolean monitorReadWriteLockFairness;

    // ---- Phase 3 ----
    /** Whether {@link DetectorType#RACE_CONDITIONS} is enabled for this run. */
    public final boolean detectRaceConditions;
    /** Whether {@link DetectorType#THREAD_LOCAL_LEAKS} is enabled for this run. */
    public final boolean detectThreadLocalLeaks;
    /** Whether {@link DetectorType#BUSY_WAITING} is enabled for this run. */
    public final boolean detectBusyWaiting;
    /** Whether {@link DetectorType#ATOMICITY_VIOLATIONS} is enabled for this run. */
    public final boolean detectAtomicityViolations;
    /** Whether {@link DetectorType#INTERRUPT_MISHANDLING} is enabled for this run. */
    public final boolean detectInterruptMishandling;

    // ---- Phase 2 Additional ----
    /** Whether {@link DetectorType#SEMAPHORE} is enabled for this run. */
    public final boolean monitorSemaphore;
    /** Whether {@link DetectorType#COMPLETABLE_FUTURE_EXCEPTIONS} is enabled for this run. */
    public final boolean detectCompletableFutureExceptions;
    /** Whether {@link DetectorType#COMPLETABLE_FUTURE_COMPLETION_LEAKS} is enabled for this run. */
    public final boolean detectCompletableFutureCompletionLeaks;
    /** Whether {@link DetectorType#VIRTUAL_THREAD_PINNING} is enabled for this run. */
    public final boolean detectVirtualThreadPinning;
    /** Whether {@link DetectorType#THREAD_POOL_DEADLOCK} is enabled for this run. */
    public final boolean detectThreadPoolDeadlocks;
    /** Whether {@link DetectorType#CONCURRENT_MODIFICATIONS} is enabled for this run. */
    public final boolean detectConcurrentModifications;
    /** Whether {@link DetectorType#LOCK_LEAKS} is enabled for this run. */
    public final boolean detectLockLeaks;
    /** Whether {@link DetectorType#SHARED_RANDOM} is enabled for this run. */
    public final boolean detectSharedRandom;
    /** Whether {@link DetectorType#BLOCKING_QUEUE} is enabled for this run. */
    public final boolean detectBlockingQueueIssues;
    /** Whether {@link DetectorType#CONDITION_VARIABLES} is enabled for this run. */
    public final boolean detectConditionVariableIssues;
    /** Whether {@link DetectorType#SIMPLE_DATE_FORMAT} is enabled for this run. */
    public final boolean detectSimpleDateFormatIssues;
    /** Whether {@link DetectorType#PARALLEL_STREAMS} is enabled for this run. */
    public final boolean detectParallelStreamIssues;
    /** Whether {@link DetectorType#RESOURCE_LEAKS} is enabled for this run. */
    public final boolean detectResourceLeaks;

    // ---- Phase 2: Additional Concurrency ----
    /** Whether {@link DetectorType#COUNTDOWN_LATCH} is enabled for this run. */
    public final boolean detectCountDownLatchIssues;
    /** Whether {@link DetectorType#CYCLIC_BARRIER} is enabled for this run. */
    public final boolean detectCyclicBarrierIssues;
    /** Whether {@link DetectorType#REENTRANT_LOCK} is enabled for this run. */
    public final boolean detectReentrantLockIssues;
    /** Whether {@link DetectorType#VOLATILE_ARRAY} is enabled for this run. */
    public final boolean detectVolatileArrayIssues;
    /** Whether {@link DetectorType#DOUBLE_CHECKED_LOCKING} is enabled for this run. */
    public final boolean detectDoubleCheckedLocking;
    /** Whether {@link DetectorType#WAIT_TIMEOUT} is enabled for this run. */
    public final boolean detectWaitTimeout;
    /** Whether {@link DetectorType#LOCK_CONTENTION} is enabled for this run. */
    public final boolean detectLockContention;
    /** Whether {@link DetectorType#SYNCHRONIZED_NON_FINAL} is enabled for this run. */
    public final boolean detectSynchronizedNonFinal;
    /** Whether {@link DetectorType#MISSED_SIGNAL} is enabled for this run. */
    public final boolean detectMissedSignals;
    /** Whether {@link DetectorType#LAZY_INIT_RACE} is enabled for this run. */
    public final boolean detectLazyInitRace;

    // ---- Phase 2: Advanced Concurrency Utilities ----
    /** Whether {@link DetectorType#PHASER} is enabled for this run. */
    public final boolean detectPhaserIssues;
    /** Whether {@link DetectorType#STAMPED_LOCK} is enabled for this run. */
    public final boolean detectStampedLockIssues;
    /** Whether {@link DetectorType#EXCHANGER} is enabled for this run. */
    public final boolean detectExchangerIssues;
    /** Whether {@link DetectorType#SCHEDULED_EXECUTOR} is enabled for this run. */
    public final boolean detectScheduledExecutorIssues;
    /** Whether {@link DetectorType#FORK_JOIN_POOL} is enabled for this run. */
    public final boolean detectForkJoinPoolIssues;
    /** Whether {@link DetectorType#THREAD_FACTORY} is enabled for this run. */
    public final boolean detectThreadFactoryIssues;
    /** Whether {@link DetectorType#THREAD_LEAKS} is enabled for this run. */
    public final boolean detectThreadLeaks;
    /** Whether {@link DetectorType#SLEEP_IN_LOCK} is enabled for this run. */
    public final boolean detectSleepInLock;
    /** Whether {@link DetectorType#UNBOUNDED_QUEUE} is enabled for this run. */
    public final boolean detectUnboundedQueue;
    /** Whether {@link DetectorType#THREAD_STARVATION} is enabled for this run. */
    public final boolean detectThreadStarvation;

    // ---- Phase 5: Thread-Safety of Common Types ----
    /** Whether {@link DetectorType#CALENDAR} is enabled for this run. */
    public final boolean detectCalendarIssues;
    /** Whether {@link DetectorType#SHARED_COLLECTIONS} is enabled for this run. */
    public final boolean detectSharedCollections;
    /** Whether {@link DetectorType#TIMER} is enabled for this run. */
    public final boolean detectTimerIssues;
    /** Whether {@link DetectorType#COPY_ON_WRITE_COLLECTIONS} is enabled for this run. */
    public final boolean detectCopyOnWriteCollectionIssues;
    /** Whether {@link DetectorType#STRING_BUILDER} is enabled for this run. */
    public final boolean detectStringBuilderIssues;

    // ---- Phase 6: Virtual Thread Concurrency (Java 21+) ----
    /** Whether {@link DetectorType#STRUCTURED_CONCURRENCY} is enabled for this run. */
    public final boolean detectStructuredConcurrencyIssues;
    /** Whether {@link DetectorType#VIRTUAL_THREAD_CONTEXT_LEAKS} is enabled for this run. */
    public final boolean detectVirtualThreadContextLeaks;
    /** Whether {@link DetectorType#SCOPED_VALUE} is enabled for this run. */
    public final boolean detectScopedValueMisuse;
    /** Whether {@link DetectorType#VIRTUAL_THREAD_CPU_BOUND} is enabled for this run. */
    public final boolean detectVirtualThreadCpuBoundTasks;
    /** Whether {@link DetectorType#VIRTUAL_THREAD_CARRIER_EXHAUSTION} is enabled for this run. */
    public final boolean detectVirtualThreadCarrierExhaustion;

    // ---- Phase 7: High-Level Concurrency Patterns ----
    /** Whether {@link DetectorType#HTTP_CLIENT} is enabled for this run. */
    public final boolean detectHttpClientIssues;
    /** Whether {@link DetectorType#STREAM_CLOSING} is enabled for this run. */
    public final boolean detectStreamClosing;
    /** Whether {@link DetectorType#CACHE_CONCURRENCY} is enabled for this run. */
    public final boolean detectCacheConcurrency;
    /** Whether {@link DetectorType#COMPLETABLEFUTURE_CHAIN} is enabled for this run. */
    public final boolean detectCompletableFutureChainIssues;

    // ---- Phase 8: Lifecycle & Structural Correctness ----
    /** Whether {@link DetectorType#EXECUTOR_SHUTDOWN} is enabled for this run. */
    public final boolean detectExecutorShutdown;
    /** Whether {@link DetectorType#MUTABLE_MAP_KEY} is enabled for this run. */
    public final boolean detectMutableMapKeys;
    /** Whether {@link DetectorType#NESTED_MONITOR_LOCKOUT} is enabled for this run. */
    public final boolean detectNestedMonitorLockout;
    /** Whether {@link DetectorType#LOCK_DOWNGRADE} is enabled for this run. */
    public final boolean detectLockDowngrade;
    /** Whether {@link DetectorType#INHERITABLE_THREAD_LOCAL} is enabled for this run. */
    public final boolean detectInheritableThreadLocalMisuse;

    // ---- Phase 10: API Traps & Subtle Concurrency Bugs ----
    /** Whether {@link DetectorType#THREAD_LOCAL_CONTAMINATION} is enabled for this run. */
    public final boolean detectThreadLocalContamination;
    /** Whether {@link DetectorType#ATOMIC_NON_ATOMIC_UPDATE} is enabled for this run. */
    public final boolean detectAtomicNonAtomicUpdates;
    /** Whether {@link DetectorType#SYNCHRONIZED_COLLECTION_ITERATION} is enabled for this run. */
    public final boolean detectSynchronizedCollectionIteration;
    /** Whether {@link DetectorType#SHARED_FORMATTER} is enabled for this run. */
    public final boolean detectSharedFormatter;
    /** Whether {@link DetectorType#CONCURRENT_MAP_COMPUTE_RECURSION} is enabled for this run. */
    public final boolean detectConcurrentMapComputeRecursion;
    /** Whether {@link DetectorType#SYNCHRONIZED_ON_LITERAL} is enabled for this run. */
    public final boolean detectSynchronizedOnLiteral;
    /** Whether {@link DetectorType#PUBLIC_LOCK_EXPOSURE} is enabled for this run. */
    public final boolean detectPublicLockExposure;
    /** Whether {@link DetectorType#FORK_JOIN_TASK_BLOCKING} is enabled for this run. */
    public final boolean detectForkJoinTaskBlocking;
    /** Whether {@link DetectorType#OPTIMISTIC_READ_VALIDATION} is enabled for this run. */
    public final boolean detectOptimisticReadValidation;
    /** Whether {@link DetectorType#CF_COMMON_POOL_BLOCKING} is enabled for this run. */
    public final boolean detectCFCommonPoolBlocking;

    // ---- Phase 11: Thread-Safety of Additional Types & Patterns ----
    /** Whether {@link DetectorType#SHARED_MATCHER} is enabled for this run. */
    public final boolean detectSharedMatcher;
    /** Whether {@link DetectorType#SHARED_DECIMAL_FORMAT} is enabled for this run. */
    public final boolean detectSharedDecimalFormat;
    /** Whether {@link DetectorType#WEAK_REFERENCE_RACE} is enabled for this run. */
    public final boolean detectWeakReferenceRace;
    /** Whether {@link DetectorType#STATEFUL_LAMBDA} is enabled for this run. */
    public final boolean detectStatefulLambda;
    /** Whether {@link DetectorType#SHARED_MESSAGE_DIGEST} is enabled for this run. */
    public final boolean detectSharedMessageDigest;

    // ---- Phase 12: Operational & Hygiene Concurrency Issues ----
    /** Whether {@link DetectorType#INTERRUPT_SWALLOWING} is enabled for this run. */
    public final boolean detectInterruptSwallowing;
    /** Whether {@link DetectorType#MDC_CONTEXT_LEAK} is enabled for this run. */
    public final boolean detectMdcContextLeak;
    /** Whether {@link DetectorType#SYSTEM_PROPERTY_MUTATION} is enabled for this run. */
    public final boolean detectSystemPropertyMutation;
    /** Whether {@link DetectorType#FUTURE_IGNORED} is enabled for this run. */
    public final boolean detectFutureIgnored;
    /** Whether {@link DetectorType#EXPLICIT_GC} is enabled for this run. */
    public final boolean detectExplicitGc;
    /** Whether {@link DetectorType#DEPRECATED_THREAD_API} is enabled for this run. */
    public final boolean detectDeprecatedThreadApi;
    /** Whether {@link DetectorType#SHARED_XML_PARSER} is enabled for this run. */
    public final boolean detectSharedXmlParser;
    /** Whether {@link DetectorType#BOXED_PRIMITIVE_LOCK} is enabled for this run. */
    public final boolean detectBoxedPrimitiveLock;
    /** Whether {@link DetectorType#SHARED_TIMEZONE} is enabled for this run. */
    public final boolean detectSharedTimeZone;
    /** Whether {@link DetectorType#UNCAUGHT_EXCEPTION_HANDLER} is enabled for this run. */
    public final boolean detectUncaughtExceptionHandler;

    // ---- Phase 13 (1.0.0+) ----
    /** Whether {@link DetectorType#DAEMON_THREAD_HYGIENE} is enabled for this run. */
    public final boolean detectDaemonThreadHygiene;
    /** Whether {@link DetectorType#NOTIFY_WITHOUT_MONITOR} is enabled for this run. */
    public final boolean detectNotifyWithoutMonitor;
    /** Whether {@link DetectorType#SHARED_SECURE_RANDOM} is enabled for this run. */
    public final boolean detectSharedSecureRandom;
    /** Whether {@link DetectorType#WEAK_HASH_MAP_SHARED} is enabled for this run. */
    public final boolean detectWeakHashMapShared;
    /** Whether {@link DetectorType#JDBC_CONNECTION_SHARED} is enabled for this run. */
    public final boolean detectJdbcConnectionShared;

    // ---- Phase 14 (1.7.0+) ----
    /** Whether {@link DetectorType#SHARED_STATEFUL_CRYPTO} is enabled for this run. */
    public final boolean detectSharedStatefulCrypto;
    /** Whether {@link DetectorType#CONCURRENT_MAP_CHECK_THEN_ACT} is enabled for this run. */
    public final boolean detectConcurrentMapCheckThenAct;
    /** Whether {@link DetectorType#SHARED_DEFLATER} is enabled for this run. */
    public final boolean detectSharedDeflater;
    /** Whether {@link DetectorType#THIS_ESCAPE} is enabled for this run. */
    public final boolean detectThisEscape;
    /** Whether {@link DetectorType#THREAD_LOCAL_RANDOM_MISUSE} is enabled for this run. */
    public final boolean detectThreadLocalRandomMisuse;

    // ---- Phase 15 (1.8.0+) ----
    /** Whether {@link DetectorType#COMPLETABLE_FUTURE_OBTRUDE_ABUSE} is enabled for this run. */
    public final boolean detectCompletableFutureObtrudeAbuse;
    /** Whether {@link DetectorType#SPURIOUS_WAKEUP_HAZARD} is enabled for this run. */
    public final boolean detectSpuriousWakeupHazard;
    /** Whether {@link DetectorType#LOCK_UPGRADE_DEADLOCK} is enabled for this run. */
    public final boolean detectLockUpgradeDeadlock;
    /** Whether {@link DetectorType#TRY_LOCK_MISUSE} is enabled for this run. */
    public final boolean detectTryLockMisuse;
    /** Whether {@link DetectorType#COMPLETABLE_FUTURE_BLOCKING_CALLBACK} is enabled for this run. */
    public final boolean detectCFBlockingCallback;

    // ---- Phase 16: JDK 25/26 preview-era detectors ----
    /** Whether {@link DetectorType#STABLE_VALUE_MISUSE} is enabled for this run. */
    public final boolean detectStableValueMisuse;
    /** Whether {@link DetectorType#STRUCTURED_TASK_SCOPE_MISUSE} is enabled for this run. */
    public final boolean detectStructuredTaskScopeMisuse;
    /** Whether {@link DetectorType#GATHERER_CONCURRENCY_MISUSE} is enabled for this run. */
    public final boolean detectGathererConcurrencyMisuse;

    // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----
    /** Whether {@link DetectorType#SHARED_BYTE_BUFFER} is enabled for this run. */
    public final boolean detectSharedByteBuffer;
    /** Whether {@link DetectorType#SHARED_CHARSET_CODER} is enabled for this run. */
    public final boolean detectSharedCharsetCoder;
    /** Whether {@link DetectorType#SHARED_CHECKSUM} is enabled for this run. */
    public final boolean detectSharedChecksum;
    /** Whether {@link DetectorType#FILE_CHANNEL_POSITION_RACE} is enabled for this run. */
    public final boolean detectFileChannelPositionRace;
    /** Whether {@link DetectorType#SHARED_ITERATOR} is enabled for this run. */
    public final boolean detectSharedIterator;
    /** Whether {@link DetectorType#HIGH_CONTENTION_ATOMIC} is enabled for this run. */
    public final boolean detectHighContentionAtomic;
    /** Whether {@link DetectorType#SHARED_JSON_MAPPER_RECONFIG} is enabled for this run. */
    public final boolean detectSharedJsonMapperReconfig;

    // ---- Phase 18: JDK 25/26 GA-era concurrency detectors ----
    /** Whether {@link DetectorType#LAZY_CONSTANT_MISUSE} is enabled for this run. */
    public final boolean detectLazyConstantMisuse;
    /** Whether {@link DetectorType#FINAL_FIELD_MUTATION} is enabled for this run. */
    public final boolean detectFinalFieldMutation;
    /** Whether {@link DetectorType#SHARED_KDF} is enabled for this run. */
    public final boolean detectSharedKdf;
    /** Whether {@link DetectorType#LATCH_MISUSE} is enabled for this run. */
    public final boolean detectLatchMisuse;
    /** Whether {@link DetectorType#EXECUTOR_DEADLOCK} is enabled for this run. */
    public final boolean detectExecutorDeadlock;
    /** Whether {@link DetectorType#FUTURE_BLOCKING} is enabled for this run. */
    public final boolean detectFutureBlocking;
    /** Whether {@link DetectorType#FLOW_PUBLISHER_CONCURRENCY} is enabled for this run. */
    public final boolean detectFlowPublisherConcurrency;
    /** Whether {@link DetectorType#CONFINED_ARENA_THREAD_ESCAPE} is enabled for this run. */
    public final boolean detectConfinedArenaThreadEscape;
    /** Whether {@link DetectorType#SHARED_MEMORY_SEGMENT_RACE} is enabled for this run. */
    public final boolean detectSharedMemorySegmentRace;
    /** Whether {@link DetectorType#VAR_HANDLE_NON_ATOMIC_UPDATE} is enabled for this run. */
    public final boolean detectVarHandleNonAtomicUpdate;
    /** Whether {@link DetectorType#RECORD_MUTABLE_COMPONENT_LEAK} is enabled for this run. */
    public final boolean detectRecordMutableComponentLeak;
    /** Whether {@link DetectorType#STATIC_INIT_DEADLOCK} is enabled for this run. */
    public final boolean detectStaticInitDeadlock;
    /** Whether {@link DetectorType#VIRTUAL_THREAD_POOLING} is enabled for this run. */
    public final boolean detectVirtualThreadPooling;
    /** Whether {@link DetectorType#PLATFORM_THREAD_PER_TASK} is enabled for this run. */
    public final boolean detectPlatformThreadPerTask;
    /** Whether {@link DetectorType#SHARED_SPLITTABLE_RANDOM} is enabled for this run. */
    public final boolean detectSharedSplittableRandom;
    /** Whether {@link DetectorType#COMPLETABLE_FUTURE_COMPLETION_RACE} is enabled for this run. */
    public final boolean detectCompletableFutureCompletionRace;
    /** Whether {@link DetectorType#COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION} is enabled for this run. */
    public final boolean detectCompletableFutureCancellationPropagation;
    /** Whether {@link DetectorType#COMPLETABLE_FUTURE_COMBINATOR_MISUSE} is enabled for this run. */
    public final boolean detectCompletableFutureCombinatorMisuse;
    /** Whether {@link DetectorType#LAMBDA_LOST_UPDATE} is enabled for this run. */
    public final boolean detectLambdaLostUpdate;
    /** Whether {@link DetectorType#VIRTUAL_THREAD_RESOURCE_SATURATION} is enabled for this run. */
    public final boolean detectVirtualThreadResourceSaturation;
    /** Whether {@link DetectorType#VIRTUAL_THREAD_MONITOR_SERIALIZATION} is enabled for this run. */
    public final boolean detectVirtualThreadMonitorSerialization;
    /** Whether {@link DetectorType#THREAD_LOCAL_CACHE_DEGRADATION} is enabled for this run. */
    public final boolean detectThreadLocalCacheDegradation;
    /** Whether {@link DetectorType#SCOPE_JOINER_MISUSE} is enabled for this run. */
    public final boolean detectScopeJoinerMisuse;
    /** Whether {@link DetectorType#SCOPE_CONFIGURATION_MISUSE} is enabled for this run. */
    public final boolean detectScopeConfigurationMisuse;
    /** Whether {@link DetectorType#SCOPE_RESULT_ESCAPE} is enabled for this run. */
    public final boolean detectScopeResultEscape;
    /** Whether {@link DetectorType#LAZY_COLLECTION_MISUSE} is enabled for this run. */
    public final boolean detectLazyCollectionMisuse;

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

    private AsyncTestConfig(Builder b, Set<DetectorType> enabled) {
        enabledDetectors               = Collections.unmodifiableSet(EnumSet.copyOf(enabled));
        excludedIds                    = Set.copyOf(b.excludeIds);
        threads                        = b.threads;
        invocations                    = b.invocations;
        useVirtualThreads              = b.useVirtualThreads;
        timeoutMs                      = b.timeoutMs;
        virtualThreadStressMode        = b.virtualThreadStressMode;
        detectAll                      = b.detectAll;
        replaySeed                     = b.replaySeed;
        failOn                         = b.failOn;
        minTrust                       = b.minTrust;
        detectDeadlocks                = enabled.contains(DetectorType.DEADLOCKS);
        detectVisibility               = enabled.contains(DetectorType.VISIBILITY);
        detectLivelocks                = enabled.contains(DetectorType.LIVELOCKS);
        detectFalseSharing             = enabled.contains(DetectorType.FALSE_SHARING);
        detectWakeupIssues             = enabled.contains(DetectorType.WAKEUP_ISSUES);
        validateConstructorSafety      = enabled.contains(DetectorType.CONSTRUCTOR_SAFETY);
        detectABAProblem               = enabled.contains(DetectorType.ABA_PROBLEM);
        validateLockOrder              = enabled.contains(DetectorType.LOCK_ORDER);
        monitorSynchronizers           = enabled.contains(DetectorType.SYNCHRONIZERS);
        monitorThreadPool              = enabled.contains(DetectorType.THREAD_POOL);
        detectMemoryOrderingViolations = enabled.contains(DetectorType.MEMORY_ORDERING);
        monitorAsyncPipeline           = enabled.contains(DetectorType.ASYNC_PIPELINE);
        monitorReadWriteLockFairness   = enabled.contains(DetectorType.READ_WRITE_LOCK_FAIRNESS);
        detectRaceConditions           = enabled.contains(DetectorType.RACE_CONDITIONS);
        detectThreadLocalLeaks         = enabled.contains(DetectorType.THREAD_LOCAL_LEAKS);
        detectBusyWaiting              = enabled.contains(DetectorType.BUSY_WAITING);
        detectAtomicityViolations      = enabled.contains(DetectorType.ATOMICITY_VIOLATIONS);
        detectInterruptMishandling     = enabled.contains(DetectorType.INTERRUPT_MISHANDLING);
        monitorSemaphore               = enabled.contains(DetectorType.SEMAPHORE);
        detectCompletableFutureExceptions = enabled.contains(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS);
        detectCompletableFutureCompletionLeaks = enabled.contains(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS);
        detectVirtualThreadPinning     = enabled.contains(DetectorType.VIRTUAL_THREAD_PINNING);
        detectThreadPoolDeadlocks      = enabled.contains(DetectorType.THREAD_POOL_DEADLOCK);
        detectConcurrentModifications  = enabled.contains(DetectorType.CONCURRENT_MODIFICATIONS);
        detectLockLeaks                = enabled.contains(DetectorType.LOCK_LEAKS);
        detectSharedRandom             = enabled.contains(DetectorType.SHARED_RANDOM);
        detectBlockingQueueIssues      = enabled.contains(DetectorType.BLOCKING_QUEUE);
        detectConditionVariableIssues  = enabled.contains(DetectorType.CONDITION_VARIABLES);
        detectSimpleDateFormatIssues   = enabled.contains(DetectorType.SIMPLE_DATE_FORMAT);
        detectParallelStreamIssues     = enabled.contains(DetectorType.PARALLEL_STREAMS);
        detectResourceLeaks            = enabled.contains(DetectorType.RESOURCE_LEAKS);
        detectCountDownLatchIssues     = enabled.contains(DetectorType.COUNTDOWN_LATCH);
        detectCyclicBarrierIssues      = enabled.contains(DetectorType.CYCLIC_BARRIER);
        detectReentrantLockIssues      = enabled.contains(DetectorType.REENTRANT_LOCK);
        detectVolatileArrayIssues      = enabled.contains(DetectorType.VOLATILE_ARRAY);
        detectDoubleCheckedLocking     = enabled.contains(DetectorType.DOUBLE_CHECKED_LOCKING);
        detectWaitTimeout              = enabled.contains(DetectorType.WAIT_TIMEOUT);
        detectLockContention           = enabled.contains(DetectorType.LOCK_CONTENTION);
        detectSynchronizedNonFinal     = enabled.contains(DetectorType.SYNCHRONIZED_NON_FINAL);
        detectMissedSignals            = enabled.contains(DetectorType.MISSED_SIGNAL);
        detectLazyInitRace             = enabled.contains(DetectorType.LAZY_INIT_RACE);
        detectPhaserIssues             = enabled.contains(DetectorType.PHASER);
        detectStampedLockIssues        = enabled.contains(DetectorType.STAMPED_LOCK);
        detectExchangerIssues          = enabled.contains(DetectorType.EXCHANGER);
        detectScheduledExecutorIssues  = enabled.contains(DetectorType.SCHEDULED_EXECUTOR);
        detectForkJoinPoolIssues       = enabled.contains(DetectorType.FORK_JOIN_POOL);
        detectThreadFactoryIssues      = enabled.contains(DetectorType.THREAD_FACTORY);
        detectThreadLeaks              = enabled.contains(DetectorType.THREAD_LEAKS);
        detectSleepInLock              = enabled.contains(DetectorType.SLEEP_IN_LOCK);
        detectUnboundedQueue           = enabled.contains(DetectorType.UNBOUNDED_QUEUE);
        detectThreadStarvation         = enabled.contains(DetectorType.THREAD_STARVATION);
        detectCalendarIssues           = enabled.contains(DetectorType.CALENDAR);
        detectSharedCollections        = enabled.contains(DetectorType.SHARED_COLLECTIONS);
        detectTimerIssues              = enabled.contains(DetectorType.TIMER);
        detectCopyOnWriteCollectionIssues = enabled.contains(DetectorType.COPY_ON_WRITE_COLLECTIONS);
        detectStringBuilderIssues        = enabled.contains(DetectorType.STRING_BUILDER);
        detectStructuredConcurrencyIssues    = enabled.contains(DetectorType.STRUCTURED_CONCURRENCY);
        detectVirtualThreadContextLeaks      = enabled.contains(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS);
        detectScopedValueMisuse              = enabled.contains(DetectorType.SCOPED_VALUE);
        detectVirtualThreadCpuBoundTasks     = enabled.contains(DetectorType.VIRTUAL_THREAD_CPU_BOUND);
        detectVirtualThreadCarrierExhaustion = enabled.contains(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION);
        detectHttpClientIssues           = enabled.contains(DetectorType.HTTP_CLIENT);
        detectStreamClosing              = enabled.contains(DetectorType.STREAM_CLOSING);
        detectCacheConcurrency           = enabled.contains(DetectorType.CACHE_CONCURRENCY);
        detectCompletableFutureChainIssues = enabled.contains(DetectorType.COMPLETABLEFUTURE_CHAIN);
        detectExecutorShutdown           = enabled.contains(DetectorType.EXECUTOR_SHUTDOWN);
        detectMutableMapKeys             = enabled.contains(DetectorType.MUTABLE_MAP_KEY);
        detectNestedMonitorLockout       = enabled.contains(DetectorType.NESTED_MONITOR_LOCKOUT);
        detectLockDowngrade              = enabled.contains(DetectorType.LOCK_DOWNGRADE);
        detectInheritableThreadLocalMisuse = enabled.contains(DetectorType.INHERITABLE_THREAD_LOCAL);
        detectThreadLocalContamination     = enabled.contains(DetectorType.THREAD_LOCAL_CONTAMINATION);
        detectAtomicNonAtomicUpdates       = enabled.contains(DetectorType.ATOMIC_NON_ATOMIC_UPDATE);
        detectSynchronizedCollectionIteration = enabled.contains(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION);
        detectSharedFormatter              = enabled.contains(DetectorType.SHARED_FORMATTER);
        detectConcurrentMapComputeRecursion = enabled.contains(DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION);
        detectSynchronizedOnLiteral        = enabled.contains(DetectorType.SYNCHRONIZED_ON_LITERAL);
        detectPublicLockExposure           = enabled.contains(DetectorType.PUBLIC_LOCK_EXPOSURE);
        detectForkJoinTaskBlocking         = enabled.contains(DetectorType.FORK_JOIN_TASK_BLOCKING);
        detectOptimisticReadValidation     = enabled.contains(DetectorType.OPTIMISTIC_READ_VALIDATION);
        detectCFCommonPoolBlocking         = enabled.contains(DetectorType.CF_COMMON_POOL_BLOCKING);
        detectSharedMatcher            = enabled.contains(DetectorType.SHARED_MATCHER);
        detectSharedDecimalFormat      = enabled.contains(DetectorType.SHARED_DECIMAL_FORMAT);
        detectWeakReferenceRace        = enabled.contains(DetectorType.WEAK_REFERENCE_RACE);
        detectStatefulLambda           = enabled.contains(DetectorType.STATEFUL_LAMBDA);
        detectSharedMessageDigest      = enabled.contains(DetectorType.SHARED_MESSAGE_DIGEST);
        detectInterruptSwallowing      = enabled.contains(DetectorType.INTERRUPT_SWALLOWING);
        detectMdcContextLeak           = enabled.contains(DetectorType.MDC_CONTEXT_LEAK);
        detectSystemPropertyMutation   = enabled.contains(DetectorType.SYSTEM_PROPERTY_MUTATION);
        detectFutureIgnored            = enabled.contains(DetectorType.FUTURE_IGNORED);
        detectExplicitGc               = enabled.contains(DetectorType.EXPLICIT_GC);
        detectDeprecatedThreadApi      = enabled.contains(DetectorType.DEPRECATED_THREAD_API);
        detectSharedXmlParser          = enabled.contains(DetectorType.SHARED_XML_PARSER);
        detectBoxedPrimitiveLock       = enabled.contains(DetectorType.BOXED_PRIMITIVE_LOCK);
        detectSharedTimeZone           = enabled.contains(DetectorType.SHARED_TIMEZONE);
        detectUncaughtExceptionHandler = enabled.contains(DetectorType.UNCAUGHT_EXCEPTION_HANDLER);
        // Phase 13
        detectDaemonThreadHygiene   = enabled.contains(DetectorType.DAEMON_THREAD_HYGIENE);
        detectNotifyWithoutMonitor  = enabled.contains(DetectorType.NOTIFY_WITHOUT_MONITOR);
        detectSharedSecureRandom    = enabled.contains(DetectorType.SHARED_SECURE_RANDOM);
        detectWeakHashMapShared     = enabled.contains(DetectorType.WEAK_HASH_MAP_SHARED);
        detectJdbcConnectionShared  = enabled.contains(DetectorType.JDBC_CONNECTION_SHARED);
        // Phase 14
        detectSharedStatefulCrypto      = enabled.contains(DetectorType.SHARED_STATEFUL_CRYPTO);
        detectConcurrentMapCheckThenAct = enabled.contains(DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT);
        detectSharedDeflater            = enabled.contains(DetectorType.SHARED_DEFLATER);
        detectThisEscape                = enabled.contains(DetectorType.THIS_ESCAPE);
        detectThreadLocalRandomMisuse   = enabled.contains(DetectorType.THREAD_LOCAL_RANDOM_MISUSE);
        // Phase 15
        detectCompletableFutureObtrudeAbuse = enabled.contains(DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE);
        detectSpuriousWakeupHazard          = enabled.contains(DetectorType.SPURIOUS_WAKEUP_HAZARD);
        detectLockUpgradeDeadlock           = enabled.contains(DetectorType.LOCK_UPGRADE_DEADLOCK);
        detectTryLockMisuse                 = enabled.contains(DetectorType.TRY_LOCK_MISUSE);
        detectCFBlockingCallback            = enabled.contains(DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK);
        // Phase 16 (JDK 25/26)
        detectStableValueMisuse             = enabled.contains(DetectorType.STABLE_VALUE_MISUSE);
        detectStructuredTaskScopeMisuse     = enabled.contains(DetectorType.STRUCTURED_TASK_SCOPE_MISUSE);
        detectGathererConcurrencyMisuse     = enabled.contains(DetectorType.GATHERER_CONCURRENCY_MISUSE);
        // Phase 17
        detectSharedByteBuffer          = enabled.contains(DetectorType.SHARED_BYTE_BUFFER);
        detectSharedCharsetCoder        = enabled.contains(DetectorType.SHARED_CHARSET_CODER);
        detectSharedChecksum            = enabled.contains(DetectorType.SHARED_CHECKSUM);
        detectFileChannelPositionRace   = enabled.contains(DetectorType.FILE_CHANNEL_POSITION_RACE);
        detectSharedIterator            = enabled.contains(DetectorType.SHARED_ITERATOR);
        detectHighContentionAtomic      = enabled.contains(DetectorType.HIGH_CONTENTION_ATOMIC);
        detectSharedJsonMapperReconfig  = enabled.contains(DetectorType.SHARED_JSON_MAPPER_RECONFIG);
        // Phase 18 (JDK 25/26 GA)
        detectLazyConstantMisuse        = enabled.contains(DetectorType.LAZY_CONSTANT_MISUSE);
        detectFinalFieldMutation        = enabled.contains(DetectorType.FINAL_FIELD_MUTATION);
        detectSharedKdf                 = enabled.contains(DetectorType.SHARED_KDF);
        detectLatchMisuse               = enabled.contains(DetectorType.LATCH_MISUSE);
        detectExecutorDeadlock          = enabled.contains(DetectorType.EXECUTOR_DEADLOCK);
        detectFutureBlocking            = enabled.contains(DetectorType.FUTURE_BLOCKING);
        detectFlowPublisherConcurrency  = enabled.contains(DetectorType.FLOW_PUBLISHER_CONCURRENCY);
        detectConfinedArenaThreadEscape  = enabled.contains(DetectorType.CONFINED_ARENA_THREAD_ESCAPE);
        detectSharedMemorySegmentRace    = enabled.contains(DetectorType.SHARED_MEMORY_SEGMENT_RACE);
        detectVarHandleNonAtomicUpdate   = enabled.contains(DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE);
        detectRecordMutableComponentLeak = enabled.contains(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK);
        detectStaticInitDeadlock         = enabled.contains(DetectorType.STATIC_INIT_DEADLOCK);
        detectVirtualThreadPooling       = enabled.contains(DetectorType.VIRTUAL_THREAD_POOLING);
        detectPlatformThreadPerTask      = enabled.contains(DetectorType.PLATFORM_THREAD_PER_TASK);
        detectSharedSplittableRandom     = enabled.contains(DetectorType.SHARED_SPLITTABLE_RANDOM);
        detectCompletableFutureCompletionRace = enabled.contains(DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE);
        detectCompletableFutureCancellationPropagation = enabled.contains(DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION);
        detectCompletableFutureCombinatorMisuse = enabled.contains(DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE);
        detectLambdaLostUpdate           = enabled.contains(DetectorType.LAMBDA_LOST_UPDATE);
        detectVirtualThreadResourceSaturation = enabled.contains(DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION);
        detectVirtualThreadMonitorSerialization = enabled.contains(DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION);
        detectThreadLocalCacheDegradation = enabled.contains(DetectorType.THREAD_LOCAL_CACHE_DEGRADATION);
        detectScopeJoinerMisuse = enabled.contains(DetectorType.SCOPE_JOINER_MISUSE);
        detectScopeConfigurationMisuse = enabled.contains(DetectorType.SCOPE_CONFIGURATION_MISUSE);
        detectScopeResultEscape = enabled.contains(DetectorType.SCOPE_RESULT_ESCAPE);
        detectLazyCollectionMisuse = enabled.contains(DetectorType.LAZY_COLLECTION_MISUSE);
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
     * {@return the detectors this run enables, as an unmodifiable set}
     *
     * <p>The one answer to "which detectors run": {@code detectAll}, the per-detector switches,
     * {@code includes}, {@code excludes} and a preset are all resolved into it, and every public
     * detector flag on this class reads from it.
     *
     * @since 2.0.0
     */
    public Set<DetectorType> enabledDetectors() {
        return enabledDetectors;
    }

    /**
     * {@return whether this run enables {@code type}}
     *
     * @param type the detector to ask about
     * @since 2.0.0
     */
    public boolean isEnabled(DetectorType type) {
        return enabledDetectors.contains(type);
    }

    /**
     * {@return whether this run enables the detector with {@code id}}
     *
     * <p>An id that names a {@link DetectorType} is that type's selection. Any other id belongs to
     * a third-party detector (#919): it is enabled unless the test switched it off with
     * {@code excludeIds}, because the detector is on the classpath only when the user put it there.
     *
     * @param id a {@link se.deversity.asynctest.spi.Detector#id() detector id}
     * @since 2.0.0
     */
    public boolean isEnabled(String id) {
        for (DetectorType type : DetectorType.values()) {
            if (type.name().equals(id)) {
                return isEnabled(type);
            }
        }
        return !excludedIds.contains(id);
    }

    /**
     * {@return the detector ids this run switched off, as an unmodifiable set}
     *
     * @since 2.0.0
     */
    public Set<String> excludedIds() {
        return excludedIds;
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

        // Resolve the selection: includes(), else a preset other than ALL/STRICT, else
        // detectAll(). Each is expressed as detectAll plus the excludes that carve the selection
        // out of every type, so build() resolves it like any other. The per-detector boolean
        // attributes that used to feed build() were removed in 2.0.0 (#920), so detectAll = false
        // under ALL selects nothing; under 1.x it left 144 of those attributes at their default
        // of true. User-supplied excludes() always layer on top and win on conflict.
        Preset preset = ann.preset();
        boolean effectiveDetectAll = true;
        Set<DetectorType> effectiveExcludes = EnumSet.noneOf(DetectorType.class);
        if (ann.includes().length > 0) {
            Set<DetectorType> included = EnumSet.noneOf(DetectorType.class);
            included.addAll(Arrays.asList(ann.includes()));
            for (DetectorType t : DetectorType.values()) {
                if (!included.contains(t)) effectiveExcludes.add(t);
            }
        } else if (preset.isAll()) {
            effectiveDetectAll = ann.detectAll();
            if (!effectiveDetectAll) {
                effectiveExcludes.addAll(EnumSet.allOf(DetectorType.class));
            }
        } else {
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
            .minTrust(ann.minTrust())
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
            .excludeIds(ann.excludeIds())
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
        private TrustTier minTrust                 = TrustTier.ADVISORY;
        private boolean enableBenchmarking = false;
        private double benchmarkRegressionThreshold = 0.2;
        private boolean failOnBenchmarkRegression = false;
        private String keygenAccountId = "";
        private String keygenApiKey = "";
        private String keygenProductId = "";
        private String lemonSqueezyStore = "";
        private String licenseKey = "";
        private boolean licenseMockMode = false;
        // The per-detector setters, as one set: a setter adds or removes its own type, and
        // build() resolves the whole selection from it (#917). Deadlock detection is on by
        // default, as its boolean was.
        private final Set<DetectorType> explicit = EnumSet.of(DetectorType.DEADLOCKS);
        private final Set<String> excludeIds = new java.util.LinkedHashSet<>();
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
         * Sets {@link AsyncTestConfig#minTrust}.
         * @param v the value to use; {@code null} restores the default floor
         * @return this builder
         */
        public Builder minTrust(TrustTier v)                 { minTrust = (v != null) ? v : TrustTier.ADVISORY; return this; }
        /**
         * Sets {@link AsyncTestConfig#detectDeadlocks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectDeadlocks(boolean v) { return flag(DetectorType.DEADLOCKS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVisibility}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVisibility(boolean v) { return flag(DetectorType.VISIBILITY, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLivelocks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLivelocks(boolean v) { return flag(DetectorType.LIVELOCKS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectFalseSharing}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFalseSharing(boolean v) { return flag(DetectorType.FALSE_SHARING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectWakeupIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectWakeupIssues(boolean v) { return flag(DetectorType.WAKEUP_ISSUES, v); }
        /**
         * Sets {@link AsyncTestConfig#validateConstructorSafety}.
         * @param v the value to use
         * @return this builder
         */
        public Builder validateConstructorSafety(boolean v) { return flag(DetectorType.CONSTRUCTOR_SAFETY, v); }
        /**
         * Sets {@link AsyncTestConfig#detectABAProblem}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectABAProblem(boolean v) { return flag(DetectorType.ABA_PROBLEM, v); }
        /**
         * Sets {@link AsyncTestConfig#validateLockOrder}.
         * @param v the value to use
         * @return this builder
         */
        public Builder validateLockOrder(boolean v) { return flag(DetectorType.LOCK_ORDER, v); }
        /**
         * Sets {@link AsyncTestConfig#monitorSynchronizers}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorSynchronizers(boolean v) { return flag(DetectorType.SYNCHRONIZERS, v); }
        /**
         * Sets {@link AsyncTestConfig#monitorThreadPool}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorThreadPool(boolean v) { return flag(DetectorType.THREAD_POOL, v); }
        /**
         * Sets {@link AsyncTestConfig#detectMemoryOrderingViolations}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectMemoryOrderingViolations(boolean v) { return flag(DetectorType.MEMORY_ORDERING, v); }
        /**
         * Sets {@link AsyncTestConfig#monitorAsyncPipeline}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorAsyncPipeline(boolean v) { return flag(DetectorType.ASYNC_PIPELINE, v); }
        /**
         * Sets {@link AsyncTestConfig#monitorReadWriteLockFairness}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorReadWriteLockFairness(boolean v) { return flag(DetectorType.READ_WRITE_LOCK_FAIRNESS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectRaceConditions}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectRaceConditions(boolean v) { return flag(DetectorType.RACE_CONDITIONS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLocalLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLocalLeaks(boolean v) { return flag(DetectorType.THREAD_LOCAL_LEAKS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectBusyWaiting}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectBusyWaiting(boolean v) { return flag(DetectorType.BUSY_WAITING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectAtomicityViolations}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectAtomicityViolations(boolean v) { return flag(DetectorType.ATOMICITY_VIOLATIONS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectInterruptMishandling}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectInterruptMishandling(boolean v) { return flag(DetectorType.INTERRUPT_MISHANDLING, v); }
        /**
         * Sets {@link AsyncTestConfig#monitorSemaphore}.
         * @param v the value to use
         * @return this builder
         */
        public Builder monitorSemaphore(boolean v) { return flag(DetectorType.SEMAPHORE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureExceptions}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureExceptions(boolean v) { return flag(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureCompletionLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureCompletionLeaks(boolean v) { return flag(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadPinning}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadPinning(boolean v) { return flag(DetectorType.VIRTUAL_THREAD_PINNING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThreadPoolDeadlocks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadPoolDeadlocks(boolean v) { return flag(DetectorType.THREAD_POOL_DEADLOCK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectConcurrentModifications}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConcurrentModifications(boolean v) { return flag(DetectorType.CONCURRENT_MODIFICATIONS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLockLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLockLeaks(boolean v) { return flag(DetectorType.LOCK_LEAKS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedRandom}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedRandom(boolean v) { return flag(DetectorType.SHARED_RANDOM, v); }
        /**
         * Sets {@link AsyncTestConfig#detectBlockingQueueIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectBlockingQueueIssues(boolean v) { return flag(DetectorType.BLOCKING_QUEUE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectConditionVariableIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConditionVariableIssues(boolean v) { return flag(DetectorType.CONDITION_VARIABLES, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSimpleDateFormatIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSimpleDateFormatIssues(boolean v) { return flag(DetectorType.SIMPLE_DATE_FORMAT, v); }
        /**
         * Sets {@link AsyncTestConfig#detectParallelStreamIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectParallelStreamIssues(boolean v) { return flag(DetectorType.PARALLEL_STREAMS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectResourceLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectResourceLeaks(boolean v) { return flag(DetectorType.RESOURCE_LEAKS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCountDownLatchIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCountDownLatchIssues(boolean v) { return flag(DetectorType.COUNTDOWN_LATCH, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCyclicBarrierIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCyclicBarrierIssues(boolean v) { return flag(DetectorType.CYCLIC_BARRIER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectReentrantLockIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectReentrantLockIssues(boolean v) { return flag(DetectorType.REENTRANT_LOCK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVolatileArrayIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVolatileArrayIssues(boolean v) { return flag(DetectorType.VOLATILE_ARRAY, v); }
        /**
         * Sets {@link AsyncTestConfig#detectDoubleCheckedLocking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectDoubleCheckedLocking(boolean v) { return flag(DetectorType.DOUBLE_CHECKED_LOCKING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectWaitTimeout}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectWaitTimeout(boolean v) { return flag(DetectorType.WAIT_TIMEOUT, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLockContention}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLockContention(boolean v) { return flag(DetectorType.LOCK_CONTENTION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSynchronizedNonFinal}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSynchronizedNonFinal(boolean v) { return flag(DetectorType.SYNCHRONIZED_NON_FINAL, v); }
        /**
         * Sets {@link AsyncTestConfig#detectMissedSignals}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectMissedSignals(boolean v) { return flag(DetectorType.MISSED_SIGNAL, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLazyInitRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLazyInitRace(boolean v) { return flag(DetectorType.LAZY_INIT_RACE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectPhaserIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectPhaserIssues(boolean v) { return flag(DetectorType.PHASER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectStampedLockIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStampedLockIssues(boolean v) { return flag(DetectorType.STAMPED_LOCK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectExchangerIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectExchangerIssues(boolean v) { return flag(DetectorType.EXCHANGER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectScheduledExecutorIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectScheduledExecutorIssues(boolean v) { return flag(DetectorType.SCHEDULED_EXECUTOR, v); }
        /**
         * Sets {@link AsyncTestConfig#detectForkJoinPoolIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectForkJoinPoolIssues(boolean v) { return flag(DetectorType.FORK_JOIN_POOL, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThreadFactoryIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadFactoryIssues(boolean v) { return flag(DetectorType.THREAD_FACTORY, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLeaks(boolean v) { return flag(DetectorType.THREAD_LEAKS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSleepInLock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSleepInLock(boolean v) { return flag(DetectorType.SLEEP_IN_LOCK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectUnboundedQueue}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectUnboundedQueue(boolean v) { return flag(DetectorType.UNBOUNDED_QUEUE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThreadStarvation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadStarvation(boolean v) { return flag(DetectorType.THREAD_STARVATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCalendarIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCalendarIssues(boolean v) { return flag(DetectorType.CALENDAR, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedCollections}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedCollections(boolean v) { return flag(DetectorType.SHARED_COLLECTIONS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectTimerIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectTimerIssues(boolean v) { return flag(DetectorType.TIMER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCopyOnWriteCollectionIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCopyOnWriteCollectionIssues(boolean v) { return flag(DetectorType.COPY_ON_WRITE_COLLECTIONS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectStringBuilderIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStringBuilderIssues(boolean v) { return flag(DetectorType.STRING_BUILDER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectStructuredConcurrencyIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStructuredConcurrencyIssues(boolean v) { return flag(DetectorType.STRUCTURED_CONCURRENCY, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadContextLeaks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadContextLeaks(boolean v) { return flag(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, v); }
        /**
         * Sets {@link AsyncTestConfig#detectScopedValueMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectScopedValueMisuse(boolean v) { return flag(DetectorType.SCOPED_VALUE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadCpuBoundTasks}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadCpuBoundTasks(boolean v) { return flag(DetectorType.VIRTUAL_THREAD_CPU_BOUND, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadCarrierExhaustion}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadCarrierExhaustion(boolean v) { return flag(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectHttpClientIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectHttpClientIssues(boolean v) { return flag(DetectorType.HTTP_CLIENT, v); }
        /**
         * Sets {@link AsyncTestConfig#detectStreamClosing}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStreamClosing(boolean v) { return flag(DetectorType.STREAM_CLOSING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCacheConcurrency}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCacheConcurrency(boolean v) { return flag(DetectorType.CACHE_CONCURRENCY, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureChainIssues}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureChainIssues(boolean v) { return flag(DetectorType.COMPLETABLEFUTURE_CHAIN, v); }
        /**
         * Sets {@link AsyncTestConfig#detectExecutorShutdown}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectExecutorShutdown(boolean v) { return flag(DetectorType.EXECUTOR_SHUTDOWN, v); }
        /**
         * Sets {@link AsyncTestConfig#detectMutableMapKeys}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectMutableMapKeys(boolean v) { return flag(DetectorType.MUTABLE_MAP_KEY, v); }
        /**
         * Sets {@link AsyncTestConfig#detectNestedMonitorLockout}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectNestedMonitorLockout(boolean v) { return flag(DetectorType.NESTED_MONITOR_LOCKOUT, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLockDowngrade}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLockDowngrade(boolean v) { return flag(DetectorType.LOCK_DOWNGRADE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectInheritableThreadLocalMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectInheritableThreadLocalMisuse(boolean v) { return flag(DetectorType.INHERITABLE_THREAD_LOCAL, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLocalContamination}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLocalContamination(boolean v) { return flag(DetectorType.THREAD_LOCAL_CONTAMINATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectAtomicNonAtomicUpdates}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectAtomicNonAtomicUpdates(boolean v) { return flag(DetectorType.ATOMIC_NON_ATOMIC_UPDATE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSynchronizedCollectionIteration}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSynchronizedCollectionIteration(boolean v) { return flag(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedFormatter}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedFormatter(boolean v) { return flag(DetectorType.SHARED_FORMATTER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectConcurrentMapComputeRecursion}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConcurrentMapComputeRecursion(boolean v) { return flag(DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSynchronizedOnLiteral}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSynchronizedOnLiteral(boolean v) { return flag(DetectorType.SYNCHRONIZED_ON_LITERAL, v); }
        /**
         * Sets {@link AsyncTestConfig#detectPublicLockExposure}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectPublicLockExposure(boolean v) { return flag(DetectorType.PUBLIC_LOCK_EXPOSURE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectForkJoinTaskBlocking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectForkJoinTaskBlocking(boolean v) { return flag(DetectorType.FORK_JOIN_TASK_BLOCKING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectOptimisticReadValidation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectOptimisticReadValidation(boolean v) { return flag(DetectorType.OPTIMISTIC_READ_VALIDATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCFCommonPoolBlocking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCFCommonPoolBlocking(boolean v) { return flag(DetectorType.CF_COMMON_POOL_BLOCKING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedMatcher}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedMatcher(boolean v) { return flag(DetectorType.SHARED_MATCHER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedDecimalFormat}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedDecimalFormat(boolean v) { return flag(DetectorType.SHARED_DECIMAL_FORMAT, v); }
        /**
         * Sets {@link AsyncTestConfig#detectWeakReferenceRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectWeakReferenceRace(boolean v) { return flag(DetectorType.WEAK_REFERENCE_RACE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectStatefulLambda}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStatefulLambda(boolean v) { return flag(DetectorType.STATEFUL_LAMBDA, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedMessageDigest}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedMessageDigest(boolean v) { return flag(DetectorType.SHARED_MESSAGE_DIGEST, v); }
        /**
         * Sets {@link AsyncTestConfig#detectInterruptSwallowing}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectInterruptSwallowing(boolean v) { return flag(DetectorType.INTERRUPT_SWALLOWING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectMdcContextLeak}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectMdcContextLeak(boolean v) { return flag(DetectorType.MDC_CONTEXT_LEAK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSystemPropertyMutation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSystemPropertyMutation(boolean v) { return flag(DetectorType.SYSTEM_PROPERTY_MUTATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectFutureIgnored}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFutureIgnored(boolean v) { return flag(DetectorType.FUTURE_IGNORED, v); }
        /**
         * Sets {@link AsyncTestConfig#detectExplicitGc}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectExplicitGc(boolean v) { return flag(DetectorType.EXPLICIT_GC, v); }
        /**
         * Sets {@link AsyncTestConfig#detectDeprecatedThreadApi}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectDeprecatedThreadApi(boolean v) { return flag(DetectorType.DEPRECATED_THREAD_API, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedXmlParser}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedXmlParser(boolean v) { return flag(DetectorType.SHARED_XML_PARSER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectBoxedPrimitiveLock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectBoxedPrimitiveLock(boolean v) { return flag(DetectorType.BOXED_PRIMITIVE_LOCK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedTimeZone}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedTimeZone(boolean v) { return flag(DetectorType.SHARED_TIMEZONE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectUncaughtExceptionHandler}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectUncaughtExceptionHandler(boolean v) { return flag(DetectorType.UNCAUGHT_EXCEPTION_HANDLER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectDaemonThreadHygiene}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectDaemonThreadHygiene(boolean v) { return flag(DetectorType.DAEMON_THREAD_HYGIENE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectNotifyWithoutMonitor}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectNotifyWithoutMonitor(boolean v) { return flag(DetectorType.NOTIFY_WITHOUT_MONITOR, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedSecureRandom}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedSecureRandom(boolean v) { return flag(DetectorType.SHARED_SECURE_RANDOM, v); }
        /**
         * Sets {@link AsyncTestConfig#detectWeakHashMapShared}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectWeakHashMapShared(boolean v) { return flag(DetectorType.WEAK_HASH_MAP_SHARED, v); }
        /**
         * Sets {@link AsyncTestConfig#detectJdbcConnectionShared}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectJdbcConnectionShared(boolean v) { return flag(DetectorType.JDBC_CONNECTION_SHARED, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedStatefulCrypto}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedStatefulCrypto(boolean v) { return flag(DetectorType.SHARED_STATEFUL_CRYPTO, v); }
        /**
         * Sets {@link AsyncTestConfig#detectConcurrentMapCheckThenAct}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConcurrentMapCheckThenAct(boolean v) { return flag(DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedDeflater}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedDeflater(boolean v) { return flag(DetectorType.SHARED_DEFLATER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThisEscape}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThisEscape(boolean v) { return flag(DetectorType.THIS_ESCAPE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLocalRandomMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLocalRandomMisuse(boolean v) { return flag(DetectorType.THREAD_LOCAL_RANDOM_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureObtrudeAbuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureObtrudeAbuse(boolean v) { return flag(DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSpuriousWakeupHazard}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSpuriousWakeupHazard(boolean v) { return flag(DetectorType.SPURIOUS_WAKEUP_HAZARD, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLockUpgradeDeadlock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLockUpgradeDeadlock(boolean v) { return flag(DetectorType.LOCK_UPGRADE_DEADLOCK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectTryLockMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectTryLockMisuse(boolean v) { return flag(DetectorType.TRY_LOCK_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCFBlockingCallback}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCFBlockingCallback(boolean v) { return flag(DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectStableValueMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStableValueMisuse(boolean v) { return flag(DetectorType.STABLE_VALUE_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectStructuredTaskScopeMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStructuredTaskScopeMisuse(boolean v) { return flag(DetectorType.STRUCTURED_TASK_SCOPE_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectGathererConcurrencyMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectGathererConcurrencyMisuse(boolean v) { return flag(DetectorType.GATHERER_CONCURRENCY_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedByteBuffer}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedByteBuffer(boolean v) { return flag(DetectorType.SHARED_BYTE_BUFFER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedCharsetCoder}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedCharsetCoder(boolean v) { return flag(DetectorType.SHARED_CHARSET_CODER, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedChecksum}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedChecksum(boolean v) { return flag(DetectorType.SHARED_CHECKSUM, v); }
        /**
         * Sets {@link AsyncTestConfig#detectFileChannelPositionRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFileChannelPositionRace(boolean v) { return flag(DetectorType.FILE_CHANNEL_POSITION_RACE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedIterator}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedIterator(boolean v) { return flag(DetectorType.SHARED_ITERATOR, v); }
        /**
         * Sets {@link AsyncTestConfig#detectHighContentionAtomic}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectHighContentionAtomic(boolean v) { return flag(DetectorType.HIGH_CONTENTION_ATOMIC, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedJsonMapperReconfig}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedJsonMapperReconfig(boolean v) { return flag(DetectorType.SHARED_JSON_MAPPER_RECONFIG, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLazyConstantMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLazyConstantMisuse(boolean v) { return flag(DetectorType.LAZY_CONSTANT_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectFinalFieldMutation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFinalFieldMutation(boolean v) { return flag(DetectorType.FINAL_FIELD_MUTATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedKdf}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedKdf(boolean v) { return flag(DetectorType.SHARED_KDF, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLatchMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLatchMisuse(boolean v) { return flag(DetectorType.LATCH_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectExecutorDeadlock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectExecutorDeadlock(boolean v) { return flag(DetectorType.EXECUTOR_DEADLOCK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectFutureBlocking}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFutureBlocking(boolean v) { return flag(DetectorType.FUTURE_BLOCKING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectFlowPublisherConcurrency}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectFlowPublisherConcurrency(boolean v) { return flag(DetectorType.FLOW_PUBLISHER_CONCURRENCY, v); }
        /**
         * Sets {@link AsyncTestConfig#detectConfinedArenaThreadEscape}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectConfinedArenaThreadEscape(boolean v) { return flag(DetectorType.CONFINED_ARENA_THREAD_ESCAPE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedMemorySegmentRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedMemorySegmentRace(boolean v) { return flag(DetectorType.SHARED_MEMORY_SEGMENT_RACE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVarHandleNonAtomicUpdate}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVarHandleNonAtomicUpdate(boolean v) { return flag(DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectRecordMutableComponentLeak}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectRecordMutableComponentLeak(boolean v) { return flag(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectStaticInitDeadlock}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectStaticInitDeadlock(boolean v) { return flag(DetectorType.STATIC_INIT_DEADLOCK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadPooling}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadPooling(boolean v) { return flag(DetectorType.VIRTUAL_THREAD_POOLING, v); }
        /**
         * Sets {@link AsyncTestConfig#detectPlatformThreadPerTask}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectPlatformThreadPerTask(boolean v) { return flag(DetectorType.PLATFORM_THREAD_PER_TASK, v); }
        /**
         * Sets {@link AsyncTestConfig#detectSharedSplittableRandom}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectSharedSplittableRandom(boolean v) { return flag(DetectorType.SHARED_SPLITTABLE_RANDOM, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureCompletionRace}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureCompletionRace(boolean v) { return flag(DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureCancellationPropagation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureCancellationPropagation(boolean v) { return flag(DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectCompletableFutureCombinatorMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectCompletableFutureCombinatorMisuse(boolean v) { return flag(DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLambdaLostUpdate}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLambdaLostUpdate(boolean v) { return flag(DetectorType.LAMBDA_LOST_UPDATE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadResourceSaturation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadResourceSaturation(boolean v) { return flag(DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectVirtualThreadMonitorSerialization}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectVirtualThreadMonitorSerialization(boolean v) { return flag(DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectThreadLocalCacheDegradation}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectThreadLocalCacheDegradation(boolean v) { return flag(DetectorType.THREAD_LOCAL_CACHE_DEGRADATION, v); }
        /**
         * Sets {@link AsyncTestConfig#detectScopeJoinerMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectScopeJoinerMisuse(boolean v) { return flag(DetectorType.SCOPE_JOINER_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectScopeConfigurationMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectScopeConfigurationMisuse(boolean v) { return flag(DetectorType.SCOPE_CONFIGURATION_MISUSE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectScopeResultEscape}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectScopeResultEscape(boolean v) { return flag(DetectorType.SCOPE_RESULT_ESCAPE, v); }
        /**
         * Sets {@link AsyncTestConfig#detectLazyCollectionMisuse}.
         * @param v the value to use
         * @return this builder
         */
        public Builder detectLazyCollectionMisuse(boolean v) { return flag(DetectorType.LAZY_COLLECTION_MISUSE, v); }
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

        /** Adds {@code type} to the explicitly enabled detectors, or removes it. */
        private Builder flag(DetectorType type, boolean on) {
            if (on) {
                explicit.add(type);
            } else {
                explicit.remove(type);
            }
            return this;
        }

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
         * Switches detectors off by id. Mirrors {@link AsyncTest#excludeIds()}: a third-party
         * detector's own id, or a built-in's {@link DetectorType} name, which excludes that type.
         *
         * @since 2.0.0
         *
         * @param ids the detector ids to switch off; {@code null} entries and blanks are ignored
         * @return this builder
         */
        public Builder excludeIds(String... ids) {
            if (ids != null) {
                for (String id : ids) {
                    if (id != null && !id.isBlank()) {
                        excludeIds.add(id.strip());
                    }
                }
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
                // includes wins over detectAll and the per-detector setters, and the resolved
                // detectAll field has always read true for it.
                detectAll = true;
            }
            // One set, computed once, and every public detector flag is a membership test
            // against it (#917). Each flag used to have its own resolution line here,
            // (detectAll || flag) && !excludes.contains(TYPE), 146 expressions that could each
            // be wrong; nothing per type is left to write, so nothing per type can be forgotten.
            // EnumSet.copyOf takes the EnumSet branch for these, so an empty set copies safely.
            Set<DetectorType> enabled = !includes.isEmpty() ? EnumSet.copyOf(includes)
                    : detectAll ? EnumSet.allOf(DetectorType.class) : EnumSet.copyOf(explicit);
            enabled.removeAll(excludes);
            for (DetectorType type : DetectorType.values()) {
                if (excludeIds.contains(type.name())) {
                    enabled.remove(type);
                }
            }
            return new AsyncTestConfig(this, enabled);
        }
    }
}
