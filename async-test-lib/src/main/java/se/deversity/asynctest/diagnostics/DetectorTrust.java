package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.DetectorType;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The trust tier of every built-in detector, and the lookup the report path uses.
 *
 * <p><strong>Why this exists.</strong> A run with the default configuration enables all
 * {@value #DETECTOR_COUNT} detectors, and until this table existed every finding reached the
 * reader with identical weight: a recorded deadlock and "two threads touched this object and I
 * could not see your lock" printed the same way. A reader who cannot rank findings triages the
 * whole report as noise. The trust tier is the rank.
 *
 * <p><strong>How a tier is assigned.</strong> Not by opinion. {@link TrustTier#VERDICT} requires a
 * both-directions case in {@code DetectorAccuracyEvalTest}, fires on the buggy subject and silent
 * on its correctly synchronized twin, and {@code DetectorTrustCoverageTest} fails the build on a
 * promotion without one. Everything else starts at {@link TrustTier#PROMPT}, which is the honest
 * description of a detector whose silent-on-correct-code direction nobody has measured yet.
 * Lowering a tier needs no evidence; raising one does.
 *
 * <p><strong>Weakest wins, and a detector can say better.</strong> Where a detector emits findings
 * of different grades, the row carries the weakest of them, so that gating on VERDICT can never
 * admit a finding the library cannot stand behind. That rule under-rated the split detectors, so a
 * report may now grade its findings individually by implementing
 * {@link GradedFindings}; the gate then asks whether any single finding clears the thresholds
 * rather than judging the detector as a block. Seven detectors do this today. The row's tier stays
 * the answer for everything ungraded, and for the console banner when a report carries no grades.
 *
 * <p>Third-party detectors arriving through the SPI are unknown to this table and resolve to
 * {@link TrustTier#PROMPT}. The library has no evidence about somebody else's detector and does
 * not pretend to.
 *
 * @since 1.9.7
 */
@AIPublicAPI
@AIKeepInSync(
    mirrors = {
        "se.deversity.asynctest.DetectorType",
        "se.deversity.asynctest.spi.adapters.LegacyDetectorFactories",
        "docs/DETECTOR_CATALOG.md"
    },
    reason = "Every DetectorType needs exactly one row, and each row names the detector class whose "
           + "simple name keys the report map (DetectorRegistry.ifIssue) plus the short name the SPI "
           + "adapter reports. A row naming a class the factory does not create silently stops "
           + "resolving, and the finding loses its tier without anything going red.",
    enforcedBy = "se.deversity.asynctest.architecture.DetectorTrustCoverageTest"
)
@API(status = Status.EXPERIMENTAL)
public final class DetectorTrust {

    /** Number of built-in detectors classified here; equals {@code DetectorType.values().length}. */
    public static final int DETECTOR_COUNT = 146;

    /**
     * One detector's classification.
     *
     * @param type          the public {@link DetectorType} constant
     * @param detectorClass simple name of the detector class, which is the key
     *                      {@code DetectorRegistry.ifIssue} puts in the report map
     * @param spiName       short name the SPI adapter reports as {@code Violation.detector()}
     * @param tier          the weakest tier this detector can produce
     */
    public record Row(DetectorType type, String detectorClass, String spiName, TrustTier tier) { }

    private static Row row(DetectorType type, String detectorClass, String spiName, TrustTier tier) {
        return new Row(type, detectorClass, spiName, tier);
    }

    /**
     * Every built-in detector, in {@link DetectorType} declaration order.
     *
     * <p>Split-tier detectors, carrying the weakest of the grades they emit: confined-arena thread
     * escape, shared memory segment race, VarHandle non-atomic update, record mutable component
     * leak, static-init deadlock, virtual-thread pooling and shared SplittableRandom all produce a
     * verdict-grade finding on one path and a prompt-grade one on another. Platform thread-per-task
     * pairs a verdict-grade executor finding with an advisory churn threshold.
     */
    private static final List<Row> ROWS = List.of(
            row(DetectorType.DEADLOCKS, "DeadlockDetector", "Deadlocks", TrustTier.VERDICT),
            row(DetectorType.VISIBILITY, "VisibilityMonitor", "Visibility", TrustTier.PROMPT),
            row(DetectorType.LIVELOCKS, "LivelockDetector", "Livelocks", TrustTier.PROMPT),
            row(DetectorType.FALSE_SHARING, "FalseSharingDetector", "FalseSharing", TrustTier.ADVISORY),
            row(DetectorType.WAKEUP_ISSUES, "WakeupDetector", "WakeupIssues", TrustTier.PROMPT),
            row(DetectorType.CONSTRUCTOR_SAFETY, "ConstructorSafetyValidator", "ConstructorSafety", TrustTier.PROMPT),
            row(DetectorType.ABA_PROBLEM, "ABAProblemDetector", "ABAProblem", TrustTier.PROMPT),
            row(DetectorType.LOCK_ORDER, "LockOrderValidator", "LockOrder", TrustTier.VERDICT),
            row(DetectorType.SYNCHRONIZERS, "SynchronizerMonitor", "Synchronizers", TrustTier.PROMPT),
            row(DetectorType.THREAD_POOL, "ThreadPoolMonitor", "ThreadPool", TrustTier.PROMPT),
            row(DetectorType.MEMORY_ORDERING, "MemoryOrderingMonitor", "MemoryOrdering", TrustTier.PROMPT),
            row(DetectorType.ASYNC_PIPELINE, "PipelineMonitor", "AsyncPipeline", TrustTier.PROMPT),
            row(DetectorType.READ_WRITE_LOCK_FAIRNESS, "ReadWriteLockMonitor", "ReadWriteLockFairness", TrustTier.PROMPT),
            row(DetectorType.SEMAPHORE, "SemaphoreMisuseDetector", "Semaphore", TrustTier.PROMPT),
            row(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, "CompletableFutureExceptionDetector", "CompletableFutureExceptions", TrustTier.VERDICT),
            row(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, "CompletableFutureCompletionLeakDetector", "CompletableFutureCompletionLeaks", TrustTier.VERDICT),
            row(DetectorType.VIRTUAL_THREAD_PINNING, "VirtualThreadPinningDetector", "VirtualThreadPinning", TrustTier.PROMPT),
            row(DetectorType.THREAD_POOL_DEADLOCK, "ThreadPoolDeadlockDetector", "ThreadPoolDeadlock", TrustTier.PROMPT),
            row(DetectorType.CONCURRENT_MODIFICATIONS, "ConcurrentModificationDetector", "ConcurrentModifications", TrustTier.VERDICT),
            row(DetectorType.LOCK_LEAKS, "LockLeakDetector", "LockLeaks", TrustTier.VERDICT),
            row(DetectorType.SHARED_RANDOM, "SharedRandomDetector", "SharedRandom", TrustTier.PROMPT),
            row(DetectorType.BLOCKING_QUEUE, "BlockingQueueDetector", "BlockingQueue", TrustTier.PROMPT),
            row(DetectorType.CONDITION_VARIABLES, "ConditionVariableDetector", "ConditionVariables", TrustTier.PROMPT),
            row(DetectorType.SIMPLE_DATE_FORMAT, "SimpleDateFormatDetector", "SimpleDateFormat", TrustTier.PROMPT),
            row(DetectorType.PARALLEL_STREAMS, "ParallelStreamDetector", "ParallelStreams", TrustTier.PROMPT),
            row(DetectorType.RESOURCE_LEAKS, "ResourceLeakDetector", "ResourceLeaks", TrustTier.VERDICT),
            row(DetectorType.COUNTDOWN_LATCH, "CountDownLatchDetector", "CountDownLatch", TrustTier.PROMPT),
            row(DetectorType.CYCLIC_BARRIER, "CyclicBarrierDetector", "CyclicBarrier", TrustTier.PROMPT),
            row(DetectorType.REENTRANT_LOCK, "ReentrantLockDetector", "ReentrantLock", TrustTier.PROMPT),
            row(DetectorType.VOLATILE_ARRAY, "VolatileArrayDetector", "VolatileArray", TrustTier.PROMPT),
            row(DetectorType.DOUBLE_CHECKED_LOCKING, "DoubleCheckedLockingDetector", "DoubleCheckedLocking", TrustTier.PROMPT),
            row(DetectorType.WAIT_TIMEOUT, "WaitTimeoutDetector", "WaitTimeout", TrustTier.PROMPT),
            row(DetectorType.LOCK_CONTENTION, "LockContentionDetector", "LockContention", TrustTier.PROMPT),
            row(DetectorType.SYNCHRONIZED_NON_FINAL, "SynchronizedNonFinalDetector", "SynchronizedNonFinal", TrustTier.PROMPT),
            row(DetectorType.MISSED_SIGNAL, "MissedSignalDetector", "MissedSignal", TrustTier.PROMPT),
            row(DetectorType.LAZY_INIT_RACE, "LazyInitRaceDetector", "LazyInitRace", TrustTier.PROMPT),
            row(DetectorType.PHASER, "PhaserDetector", "Phaser", TrustTier.PROMPT),
            row(DetectorType.STAMPED_LOCK, "StampedLockDetector", "StampedLock", TrustTier.PROMPT),
            row(DetectorType.EXCHANGER, "ExchangerDetector", "Exchanger", TrustTier.PROMPT),
            row(DetectorType.SCHEDULED_EXECUTOR, "ScheduledExecutorDetector", "ScheduledExecutor", TrustTier.PROMPT),
            row(DetectorType.FORK_JOIN_POOL, "ForkJoinPoolDetector", "ForkJoinPool", TrustTier.PROMPT),
            row(DetectorType.THREAD_FACTORY, "ThreadFactoryDetector", "ThreadFactory", TrustTier.PROMPT),
            row(DetectorType.RACE_CONDITIONS, "RaceConditionDetector", "RaceConditions", TrustTier.PROMPT),
            row(DetectorType.THREAD_LOCAL_LEAKS, "ThreadLocalMonitor", "ThreadLocalLeaks", TrustTier.PROMPT),
            row(DetectorType.BUSY_WAITING, "BusyWaitDetector", "BusyWaiting", TrustTier.PROMPT),
            row(DetectorType.ATOMICITY_VIOLATIONS, "AtomicityValidator", "AtomicityViolations", TrustTier.PROMPT),
            row(DetectorType.INTERRUPT_MISHANDLING, "InterruptMonitor", "InterruptMishandling", TrustTier.VERDICT),
            row(DetectorType.THREAD_LEAKS, "ThreadLeakDetector", "ThreadLeaks", TrustTier.VERDICT),
            row(DetectorType.SLEEP_IN_LOCK, "SleepInLockDetector", "SleepInLock", TrustTier.PROMPT),
            row(DetectorType.UNBOUNDED_QUEUE, "UnboundedQueueDetector", "UnboundedQueue", TrustTier.PROMPT),
            row(DetectorType.THREAD_STARVATION, "ThreadStarvationDetector", "ThreadStarvation", TrustTier.PROMPT),
            row(DetectorType.CALENDAR, "CalendarDetector", "Calendar", TrustTier.PROMPT),
            row(DetectorType.SHARED_COLLECTIONS, "SharedCollectionDetector", "SharedCollections", TrustTier.PROMPT),
            row(DetectorType.TIMER, "TimerDetector", "Timer", TrustTier.PROMPT),
            row(DetectorType.COPY_ON_WRITE_COLLECTIONS, "CopyOnWriteCollectionDetector", "CopyOnWriteCollections", TrustTier.PROMPT),
            row(DetectorType.STRING_BUILDER, "StringBuilderDetector", "StringBuilder", TrustTier.PROMPT),
            row(DetectorType.STRUCTURED_CONCURRENCY, "StructuredConcurrencyMisuseDetector", "StructuredConcurrency", TrustTier.PROMPT),
            row(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, "VirtualThreadContextLeakDetector", "VirtualThreadContextLeaks", TrustTier.PROMPT),
            row(DetectorType.SCOPED_VALUE, "ScopedValueMisuseDetector", "ScopedValue", TrustTier.PROMPT),
            row(DetectorType.VIRTUAL_THREAD_CPU_BOUND, "VirtualThreadCpuBoundTaskDetector", "VirtualThreadCpuBound", TrustTier.PROMPT),
            row(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION, "VirtualThreadCarrierExhaustionDetector", "VirtualThreadCarrierExhaustion", TrustTier.PROMPT),
            row(DetectorType.HTTP_CLIENT, "HttpClientConcurrencyDetector", "HttpClient", TrustTier.PROMPT),
            row(DetectorType.STREAM_CLOSING, "StreamClosingDetector", "StreamClosing", TrustTier.PROMPT),
            row(DetectorType.CACHE_CONCURRENCY, "CacheConcurrencyDetector", "CacheConcurrency", TrustTier.PROMPT),
            row(DetectorType.COMPLETABLEFUTURE_CHAIN, "CompletableFutureChainDetector", "CompletableFutureChain", TrustTier.PROMPT),
            row(DetectorType.EXECUTOR_SHUTDOWN, "ExecutorShutdownDetector", "ExecutorShutdown", TrustTier.PROMPT),
            row(DetectorType.MUTABLE_MAP_KEY, "MutableMapKeyDetector", "MutableMapKey", TrustTier.VERDICT),
            row(DetectorType.NESTED_MONITOR_LOCKOUT, "NestedMonitorLockoutDetector", "NestedMonitorLockout", TrustTier.PROMPT),
            row(DetectorType.LOCK_DOWNGRADE, "LockDowngradeDetector", "LockDowngrade", TrustTier.PROMPT),
            row(DetectorType.INHERITABLE_THREAD_LOCAL, "InheritableThreadLocalMisuseDetector", "InheritableThreadLocal", TrustTier.PROMPT),
            row(DetectorType.THREAD_LOCAL_CONTAMINATION, "ThreadLocalContaminationDetector", "ThreadLocalContamination", TrustTier.PROMPT),
            row(DetectorType.ATOMIC_NON_ATOMIC_UPDATE, "AtomicNonAtomicUpdateDetector", "AtomicNonAtomicUpdate", TrustTier.VERDICT),
            row(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, "SynchronizedCollectionIterationDetector", "SynchronizedCollectionIteration", TrustTier.VERDICT),
            row(DetectorType.SHARED_FORMATTER, "SharedFormatterDetector", "SharedFormatter", TrustTier.PROMPT),
            row(DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, "ConcurrentMapComputeRecursionDetector", "ConcurrentMapComputeRecursion", TrustTier.VERDICT),
            row(DetectorType.SYNCHRONIZED_ON_LITERAL, "SynchronizedOnLiteralDetector", "SynchronizedOnLiteral", TrustTier.PROMPT),
            row(DetectorType.PUBLIC_LOCK_EXPOSURE, "PublicLockExposureDetector", "PublicLockExposure", TrustTier.PROMPT),
            row(DetectorType.FORK_JOIN_TASK_BLOCKING, "ForkJoinTaskBlockingDetector", "ForkJoinTaskBlocking", TrustTier.PROMPT),
            row(DetectorType.OPTIMISTIC_READ_VALIDATION, "OptimisticReadValidationDetector", "OptimisticReadValidation", TrustTier.PROMPT),
            row(DetectorType.CF_COMMON_POOL_BLOCKING, "CompletableFutureCommonPoolBlockingDetector", "CfCommonPoolBlocking", TrustTier.PROMPT),
            row(DetectorType.SHARED_MATCHER, "SharedMatcherDetector", "SharedMatcher", TrustTier.PROMPT),
            row(DetectorType.SHARED_DECIMAL_FORMAT, "SharedDecimalFormatDetector", "SharedDecimalFormat", TrustTier.PROMPT),
            row(DetectorType.WEAK_REFERENCE_RACE, "WeakReferenceRaceDetector", "WeakReferenceRace", TrustTier.PROMPT),
            row(DetectorType.STATEFUL_LAMBDA, "StatefulLambdaDetector", "StatefulLambda", TrustTier.PROMPT),
            row(DetectorType.SHARED_MESSAGE_DIGEST, "SharedMessageDigestDetector", "SharedMessageDigest", TrustTier.VERDICT),
            row(DetectorType.INTERRUPT_SWALLOWING, "InterruptSwallowingDetector", "InterruptSwallowing", TrustTier.PROMPT),
            row(DetectorType.MDC_CONTEXT_LEAK, "MdcContextLeakDetector", "MdcContextLeak", TrustTier.PROMPT),
            row(DetectorType.SYSTEM_PROPERTY_MUTATION, "SystemPropertyMutationDetector", "SystemPropertyMutation", TrustTier.PROMPT),
            row(DetectorType.FUTURE_IGNORED, "FutureIgnoredDetector", "FutureIgnored", TrustTier.PROMPT),
            row(DetectorType.EXPLICIT_GC, "ExplicitGcDetector", "ExplicitGc", TrustTier.PROMPT),
            row(DetectorType.DEPRECATED_THREAD_API, "DeprecatedThreadApiDetector", "DeprecatedThreadApi", TrustTier.PROMPT),
            row(DetectorType.SHARED_XML_PARSER, "SharedXmlParserDetector", "SharedXmlParser", TrustTier.PROMPT),
            row(DetectorType.BOXED_PRIMITIVE_LOCK, "BoxedPrimitiveLockDetector", "BoxedPrimitiveLock", TrustTier.PROMPT),
            row(DetectorType.SHARED_TIMEZONE, "SharedTimeZoneDetector", "SharedTimeZone", TrustTier.PROMPT),
            row(DetectorType.UNCAUGHT_EXCEPTION_HANDLER, "UncaughtExceptionHandlerDetector", "UncaughtExceptionHandler", TrustTier.VERDICT),
            row(DetectorType.DAEMON_THREAD_HYGIENE, "DaemonThreadHygieneDetector", "DaemonThreadHygiene", TrustTier.PROMPT),
            row(DetectorType.NOTIFY_WITHOUT_MONITOR, "NotifyWithoutMonitorDetector", "NotifyWithoutMonitor", TrustTier.PROMPT),
            row(DetectorType.SHARED_SECURE_RANDOM, "SharedSecureRandomDetector", "SharedSecureRandom", TrustTier.ADVISORY),
            row(DetectorType.WEAK_HASH_MAP_SHARED, "WeakHashMapSharedDetector", "WeakHashMapShared", TrustTier.VERDICT),
            row(DetectorType.JDBC_CONNECTION_SHARED, "JdbcConnectionSharedDetector", "JdbcConnectionShared", TrustTier.VERDICT),
            row(DetectorType.SHARED_STATEFUL_CRYPTO, "SharedStatefulCryptoDetector", "SharedStatefulCrypto", TrustTier.VERDICT),
            row(DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, "NonAtomicConcurrentMapUpdateDetector", "NonAtomicConcurrentMapUpdate", TrustTier.PROMPT),
            row(DetectorType.SHARED_DEFLATER, "SharedDeflaterDetector", "SharedDeflater", TrustTier.PROMPT),
            row(DetectorType.THIS_ESCAPE, "ThisEscapeDetector", "ThisEscape", TrustTier.PROMPT),
            row(DetectorType.THREAD_LOCAL_RANDOM_MISUSE, "ThreadLocalRandomMisuseDetector", "ThreadLocalRandomMisuse", TrustTier.PROMPT),
            row(DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE, "CompletableFutureObtrudeDetector", "CompletableFutureObtrude", TrustTier.PROMPT),
            row(DetectorType.SPURIOUS_WAKEUP_HAZARD, "SpuriousWakeupDetector", "SpuriousWakeup", TrustTier.PROMPT),
            row(DetectorType.LOCK_UPGRADE_DEADLOCK, "LockUpgradeDeadlockDetector", "LockUpgradeDeadlock", TrustTier.PROMPT),
            row(DetectorType.TRY_LOCK_MISUSE, "TryLockMisuseDetector", "TryLockMisuse", TrustTier.PROMPT),
            row(DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK, "CompletableFutureBlockingCallbackDetector", "CompletableFutureBlockingCallback", TrustTier.PROMPT),
            row(DetectorType.STABLE_VALUE_MISUSE, "StableValueMisuseDetector", "StableValueMisuse", TrustTier.PROMPT),
            row(DetectorType.STRUCTURED_TASK_SCOPE_MISUSE, "StructuredTaskScopeMisuseDetector", "StructuredTaskScopeMisuse", TrustTier.PROMPT),
            row(DetectorType.GATHERER_CONCURRENCY_MISUSE, "GathererConcurrencyMisuseDetector", "GathererConcurrencyMisuse", TrustTier.PROMPT),
            row(DetectorType.SHARED_BYTE_BUFFER, "SharedByteBufferDetector", "SharedByteBuffer", TrustTier.VERDICT),
            row(DetectorType.SHARED_CHARSET_CODER, "SharedCharsetCoderDetector", "SharedCharsetCoder", TrustTier.PROMPT),
            row(DetectorType.SHARED_CHECKSUM, "SharedChecksumDetector", "SharedChecksum", TrustTier.PROMPT),
            row(DetectorType.FILE_CHANNEL_POSITION_RACE, "FileChannelPositionRaceDetector", "FileChannelPositionRace", TrustTier.PROMPT),
            row(DetectorType.SHARED_ITERATOR, "SharedIteratorDetector", "SharedIterator", TrustTier.VERDICT),
            row(DetectorType.HIGH_CONTENTION_ATOMIC, "HighContentionAtomicDetector", "HighContentionAtomic", TrustTier.ADVISORY),
            row(DetectorType.SHARED_JSON_MAPPER_RECONFIG, "SharedJsonMapperReconfigDetector", "SharedJsonMapperReconfig", TrustTier.VERDICT),
            row(DetectorType.LAZY_CONSTANT_MISUSE, "LazyConstantMisuseDetector", "LazyConstantMisuse", TrustTier.PROMPT),
            row(DetectorType.FINAL_FIELD_MUTATION, "FinalFieldMutationDetector", "FinalFieldMutation", TrustTier.PROMPT),
            row(DetectorType.SHARED_KDF, "SharedKdfDetector", "SharedKdf", TrustTier.PROMPT),
            row(DetectorType.LATCH_MISUSE, "LatchMisuseDetector", "LatchMisuse", TrustTier.PROMPT),
            row(DetectorType.EXECUTOR_DEADLOCK, "ExecutorDeadlockDetector", "ExecutorDeadlock", TrustTier.PROMPT),
            row(DetectorType.FUTURE_BLOCKING, "FutureBlockingDetector", "FutureBlocking", TrustTier.PROMPT),
            row(DetectorType.FLOW_PUBLISHER_CONCURRENCY, "FlowPublisherConcurrencyDetector", "FlowPublisherConcurrency", TrustTier.PROMPT),
            row(DetectorType.CONFINED_ARENA_THREAD_ESCAPE, "ConfinedArenaThreadEscapeDetector", "ConfinedArenaThreadEscape", TrustTier.PROMPT),
            row(DetectorType.SHARED_MEMORY_SEGMENT_RACE, "SharedMemorySegmentRaceDetector", "SharedMemorySegmentRace", TrustTier.PROMPT),
            row(DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE, "VarHandleNonAtomicUpdateDetector", "VarHandleNonAtomicUpdate", TrustTier.PROMPT),
            row(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK, "RecordMutableComponentLeakDetector", "RecordMutableComponentLeak", TrustTier.PROMPT),
            row(DetectorType.STATIC_INIT_DEADLOCK, "StaticInitDeadlockDetector", "StaticInitDeadlock", TrustTier.PROMPT),
            row(DetectorType.VIRTUAL_THREAD_POOLING, "VirtualThreadPoolingDetector", "VirtualThreadPooling", TrustTier.PROMPT),
            row(DetectorType.PLATFORM_THREAD_PER_TASK, "PlatformThreadPerTaskDetector", "PlatformThreadPerTask", TrustTier.ADVISORY),
            row(DetectorType.SHARED_SPLITTABLE_RANDOM, "SharedSplittableRandomDetector", "SharedSplittableRandom", TrustTier.PROMPT),
            row(DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE, "CompletableFutureCompletionRaceDetector", "CompletableFutureCompletionRace", TrustTier.FACT),
            row(DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION, "CompletableFutureCancellationPropagationDetector", "CompletableFutureCancellationPropagation", TrustTier.FACT),
            row(DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE, "CompletableFutureCombinatorMisuseDetector", "CompletableFutureCombinatorMisuse", TrustTier.FACT),
            row(DetectorType.LAMBDA_LOST_UPDATE, "LambdaLostUpdateDetector", "LambdaLostUpdate", TrustTier.FACT),
            row(DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION, "VirtualThreadResourceSaturationDetector", "VirtualThreadResourceSaturation", TrustTier.FACT),
            row(DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION, "VirtualThreadMonitorSerializationDetector", "VirtualThreadMonitorSerialization", TrustTier.FACT),
            row(DetectorType.THREAD_LOCAL_CACHE_DEGRADATION, "ThreadLocalCacheDegradationDetector", "ThreadLocalCacheDegradation", TrustTier.FACT),
            row(DetectorType.SCOPE_JOINER_MISUSE, "ScopeJoinerMisuseDetector", "ScopeJoinerMisuse", TrustTier.FACT),
            row(DetectorType.SCOPE_CONFIGURATION_MISUSE, "ScopeConfigurationMisuseDetector", "ScopeConfigurationMisuse", TrustTier.FACT),
            row(DetectorType.SCOPE_RESULT_ESCAPE, "ScopeResultEscapeDetector", "ScopeResultEscape", TrustTier.FACT),
            row(DetectorType.LAZY_COLLECTION_MISUSE, "LazyCollectionMisuseDetector", "LazyCollectionMisuse", TrustTier.FACT)
    );

    private static final Map<DetectorType, Row> BY_TYPE = indexByType();
    private static final Map<String, Row> BY_NAME = indexByName();

    private DetectorTrust() { }

    private static Map<DetectorType, Row> indexByType() {
        Map<DetectorType, Row> out = new LinkedHashMap<>();
        for (Row candidate : ROWS) {
            out.put(candidate.type(), candidate);
        }
        return Map.copyOf(out);
    }

    private static Map<String, Row> indexByName() {
        Map<String, Row> out = new HashMap<>();
        for (Row candidate : ROWS) {
            out.putIfAbsent(candidate.detectorClass(), candidate);
            out.putIfAbsent(candidate.spiName(), candidate);
        }
        return Map.copyOf(out);
    }

    /** {@return every classified detector, in {@link DetectorType} declaration order} */
    public static List<Row> rows() {
        return ROWS;
    }

    /**
     * {@return the tier of a built-in detector}
     *
     * @param type the detector to classify; {@code null} yields {@link TrustTier#PROMPT}
     */
    public static TrustTier tierOf(DetectorType type) {
        Row found = type == null ? null : BY_TYPE.get(type);
        return found == null ? TrustTier.PROMPT : found.tier();
    }

    /**
     * {@return the tier of the detector that produced a finding}
     *
     * <p>Accepts either key the report path can carry: the detector class simple name that
     * {@code DetectorRegistry.ifIssue} uses for built-ins, or the short name an SPI adapter puts
     * in {@code Violation.detector()}. An unrecognised name, which is what every third-party
     * detector is, resolves to {@link TrustTier#PROMPT} rather than to a tier nobody measured.
     *
     * @param detectorName the reporting detector's name as it appears in the report map
     */
    public static TrustTier tierOfDetector(String detectorName) {
        Row found = detectorName == null ? null : BY_NAME.get(detectorName);
        return found == null ? TrustTier.PROMPT : found.tier();
    }

    /**
     * {@return the {@link DetectorType} behind a reporting detector's name, when it is a built-in}
     *
     * @param detectorName the reporting detector's name as it appears in the report map
     */
    public static Optional<DetectorType> typeOfDetector(String detectorName) {
        Row found = detectorName == null ? null : BY_NAME.get(detectorName);
        return found == null ? Optional.empty() : Optional.of(found.type());
    }
}
