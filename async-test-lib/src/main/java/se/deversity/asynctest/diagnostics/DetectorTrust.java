package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.DetectorType;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.ArrayList;
import java.util.EnumMap;
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
 * <p><strong>A pair is necessary and not sufficient.</strong> Each row also names the
 * {@link Evidence} its detector decides from, and that caps the tier: VERDICT needs a detector
 * that observes the JVM or consults the synchronization it can see, a finding that is the test's
 * own record call is at most FACT, and one decided by a thread count or a threshold is at most
 * PROMPT. The same gate refuses a row above its cap, whatever pairs it has.
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

    /**
     * What a detector decides a finding from, which bounds the tier that finding can carry.
     *
     * <p><strong>Why this exists.</strong> {@link TrustTier#VERDICT} used to need only a
     * both-directions case: fire on the bug, stay silent on the correct twin. A detector whose
     * finding is the test author's own {@code record*} call meets that rule by construction, since
     * the buggy body makes the call and the correct one does not; so does a detector that counts
     * threads or compares a number with a threshold, given a pair chosen on the right side of it.
     * {@code ExecutorDeadlockDetector} reached VERDICT at CRITICAL severity that way while its
     * finding was a lifetime counter of recorded sibling waits. A pair shows that a detector
     * separates two bodies; this class says whether what separates them is the code or the
     * recording, and {@link #cap()} is the most a finding decided that way can claim.
     *
     * <p>{@code DetectorTrustCoverageTest} fails the build on a row whose tier exceeds its class's
     * cap. For a report that grades its findings the cap is also applied at run time: the report
     * path lowers any {@link GradedFindings.Grade} above its detector's cap to the cap before the
     * gate, the banner or a listener sees it.
     *
     * <p>A detector that decides differently on different paths is classified by its weakest
     * path, because the row's tier applies to every finding it makes. A report that implements
     * {@link GradedFindings} is the exception: each of its findings carries its own tier, so its
     * class is the one behind its strongest grade, and the clamp keeps every grade at or below it.
     *
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL, since = "1.12.3")
    public enum Evidence {

        /**
         * Decided from the real JVM object or thread: the detector asks the lock, barrier, queue or
         * thread for its state, or reads events the agent wove into the bytecode. The finding does
         * not rest on the test describing its own code correctly.
         */
        OBSERVED(TrustTier.VERDICT),

        /**
         * Decided from recorded accesses, and only after consulting synchronization context the
         * detector can see: the per-round lockset {@code SelfGuard} keeps, locks declared through
         * {@link HeldLocks}, or a happens-before or ownership edge. The same accesses recorded
         * under a visible lock stay silent. Splitting the run into invocation rounds is not such
         * context on its own: two threads that touched something in one round are still just two
         * threads, which is {@link #CONTEXT_FREE}.
         */
        CONTEXTUAL(TrustTier.VERDICT),

        /**
         * The finding is essentially the recorded call itself: a call named after the defect, a
         * flag argument, or arithmetic over declared events with nothing else consulted. The
         * report is true about what was recorded; that the recording matches the code is the
         * test author's claim, so the most it can be is a {@link TrustTier#FACT}.
         */
        ASSERTED(TrustTier.FACT),

        /**
         * Recorded accesses judged by "more than one thread touched it", with no lock or ordering
         * context. Correct code sharing the object under a lock draws the same finding, so it is a
         * {@link TrustTier#PROMPT} at most.
         */
        CONTEXT_FREE(TrustTier.PROMPT),

        /**
         * A timing threshold, a ratio or a count. A number crossing a line says something may be
         * wrong, never that it is, so it is a {@link TrustTier#PROMPT} at most.
         */
        HEURISTIC(TrustTier.PROMPT);

        private final TrustTier cap;

        Evidence(TrustTier cap) {
            this.cap = cap;
        }

        /** {@return the highest tier a finding decided from this kind of evidence may carry} */
        public TrustTier cap() {
            return cap;
        }
    }

    /** A row and the evidence class its tier is capped by. */
    private record Classified(Row row, Evidence evidence) { }

    private static Classified row(DetectorType type, String detectorClass, String spiName, TrustTier tier,
                                  Evidence evidence) {
        return new Classified(new Row(type, detectorClass, spiName, tier), evidence);
    }

    /**
     * Every built-in detector, in {@link DetectorType} declaration order.
     *
     * <p>Split-tier detectors, carrying the weakest of the grades they emit: confined-arena thread
     * escape, shared memory segment race, VarHandle non-atomic update, record mutable component
     * leak, static-init deadlock and virtual-thread pooling produce a higher-grade finding on one
     * path and a prompt-grade one on another. Platform thread-per-task pairs a verdict-grade
     * executor finding with an advisory churn threshold.
     *
     * <p>The last column is the {@link Evidence} class, read from each detector's record path and
     * {@code analyze()} on 2026-09-26. Four of the graded detectors are {@link Evidence#ASSERTED}
     * although one of their paths observes the JVM: confined-arena escape and memory-segment race
     * grade a use after a recorded {@code recordClose} as VERDICT, static-init deadlock grades a
     * cycle of recorded init requests as VERDICT, and virtual-thread pooling grades two recorded
     * executions on one thread as VERDICT. The class names that weakest VERDICT-grade path, so the
     * report path clamps all four to FACT until each grades by path.
     */
    private static final List<Classified> TABLE = List.of(
            row(DetectorType.DEADLOCKS, "DeadlockDetector", "Deadlocks", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.VISIBILITY, "VisibilityMonitor", "Visibility", TrustTier.PROMPT, Evidence.CONTEXT_FREE),
            row(DetectorType.LIVELOCKS, "LivelockDetector", "Livelocks", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.FALSE_SHARING, "FalseSharingDetector", "FalseSharing", TrustTier.ADVISORY, Evidence.HEURISTIC),
            row(DetectorType.WAKEUP_ISSUES, "WakeupDetector", "WakeupIssues", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.CONSTRUCTOR_SAFETY, "ConstructorSafetyValidator", "ConstructorSafety", TrustTier.PROMPT, Evidence.OBSERVED),
            row(DetectorType.ABA_PROBLEM, "ABAProblemDetector", "ABAProblem", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.LOCK_ORDER, "LockOrderValidator", "LockOrder", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.SYNCHRONIZERS, "SynchronizerMonitor", "Synchronizers", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.THREAD_POOL, "ThreadPoolMonitor", "ThreadPool", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.MEMORY_ORDERING, "MemoryOrderingMonitor", "MemoryOrdering", TrustTier.PROMPT, Evidence.CONTEXT_FREE),
            row(DetectorType.ASYNC_PIPELINE, "PipelineMonitor", "AsyncPipeline", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.READ_WRITE_LOCK_FAIRNESS, "ReadWriteLockMonitor", "ReadWriteLockFairness", TrustTier.ADVISORY, Evidence.HEURISTIC),
            row(DetectorType.SEMAPHORE, "SemaphoreMisuseDetector", "Semaphore", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, "CompletableFutureExceptionDetector", "CompletableFutureExceptions", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, "CompletableFutureCompletionLeakDetector", "CompletableFutureCompletionLeaks", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.VIRTUAL_THREAD_PINNING, "VirtualThreadPinningDetector", "VirtualThreadPinning", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.THREAD_POOL_DEADLOCK, "ThreadPoolDeadlockDetector", "ThreadPoolDeadlock", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.CONCURRENT_MODIFICATIONS, "ConcurrentModificationDetector", "ConcurrentModifications", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.LOCK_LEAKS, "LockLeakDetector", "LockLeaks", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.SHARED_RANDOM, "SharedRandomDetector", "SharedRandom", TrustTier.ADVISORY, Evidence.HEURISTIC),
            row(DetectorType.BLOCKING_QUEUE, "BlockingQueueDetector", "BlockingQueue", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.CONDITION_VARIABLES, "ConditionVariableDetector", "ConditionVariables", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.SIMPLE_DATE_FORMAT, "SimpleDateFormatDetector", "SimpleDateFormat", TrustTier.PROMPT, Evidence.CONTEXT_FREE),
            row(DetectorType.PARALLEL_STREAMS, "ParallelStreamDetector", "ParallelStreams", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.RESOURCE_LEAKS, "ResourceLeakDetector", "ResourceLeaks", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.COUNTDOWN_LATCH, "CountDownLatchDetector", "CountDownLatch", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.CYCLIC_BARRIER, "CyclicBarrierDetector", "CyclicBarrier", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.REENTRANT_LOCK, "ReentrantLockDetector", "ReentrantLock", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.VOLATILE_ARRAY, "VolatileArrayDetector", "VolatileArray", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.DOUBLE_CHECKED_LOCKING, "DoubleCheckedLockingDetector", "DoubleCheckedLocking", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.WAIT_TIMEOUT, "WaitTimeoutDetector", "WaitTimeout", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.LOCK_CONTENTION, "LockContentionDetector", "LockContention", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.SYNCHRONIZED_NON_FINAL, "SynchronizedNonFinalDetector", "SynchronizedNonFinal", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.MISSED_SIGNAL, "MissedSignalDetector", "MissedSignal", TrustTier.PROMPT, Evidence.OBSERVED),
            row(DetectorType.LAZY_INIT_RACE, "LazyInitRaceDetector", "LazyInitRace", TrustTier.PROMPT, Evidence.CONTEXT_FREE),
            row(DetectorType.PHASER, "PhaserDetector", "Phaser", TrustTier.PROMPT, Evidence.OBSERVED),
            row(DetectorType.STAMPED_LOCK, "StampedLockDetector", "StampedLock", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.EXCHANGER, "ExchangerDetector", "Exchanger", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.SCHEDULED_EXECUTOR, "ScheduledExecutorDetector", "ScheduledExecutor", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.FORK_JOIN_POOL, "ForkJoinPoolDetector", "ForkJoinPool", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.THREAD_FACTORY, "ThreadFactoryDetector", "ThreadFactory", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.RACE_CONDITIONS, "RaceConditionDetector", "RaceConditions", TrustTier.PROMPT, Evidence.CONTEXTUAL),
            row(DetectorType.THREAD_LOCAL_LEAKS, "ThreadLocalMonitor", "ThreadLocalLeaks", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.BUSY_WAITING, "BusyWaitDetector", "BusyWaiting", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.ATOMICITY_VIOLATIONS, "AtomicityValidator", "AtomicityViolations", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.INTERRUPT_MISHANDLING, "InterruptMonitor", "InterruptMishandling", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.THREAD_LEAKS, "ThreadLeakDetector", "ThreadLeaks", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.SLEEP_IN_LOCK, "SleepInLockDetector", "SleepInLock", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.UNBOUNDED_QUEUE, "UnboundedQueueDetector", "UnboundedQueue", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.THREAD_STARVATION, "ThreadStarvationDetector", "ThreadStarvation", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.CALENDAR, "CalendarDetector", "Calendar", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.SHARED_COLLECTIONS, "SharedCollectionDetector", "SharedCollections", TrustTier.PROMPT, Evidence.OBSERVED),
            row(DetectorType.TIMER, "TimerDetector", "Timer", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.COPY_ON_WRITE_COLLECTIONS, "CopyOnWriteCollectionDetector", "CopyOnWriteCollections", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.STRING_BUILDER, "StringBuilderDetector", "StringBuilder", TrustTier.PROMPT, Evidence.CONTEXT_FREE),
            row(DetectorType.STRUCTURED_CONCURRENCY, "StructuredConcurrencyMisuseDetector", "StructuredConcurrency", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, "VirtualThreadContextLeakDetector", "VirtualThreadContextLeaks", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.SCOPED_VALUE, "ScopedValueMisuseDetector", "ScopedValue", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.VIRTUAL_THREAD_CPU_BOUND, "VirtualThreadCpuBoundTaskDetector", "VirtualThreadCpuBound", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION, "VirtualThreadCarrierExhaustionDetector", "VirtualThreadCarrierExhaustion", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.HTTP_CLIENT, "HttpClientConcurrencyDetector", "HttpClient", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.STREAM_CLOSING, "StreamClosingDetector", "StreamClosing", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.CACHE_CONCURRENCY, "CacheConcurrencyDetector", "CacheConcurrency", TrustTier.PROMPT, Evidence.CONTEXT_FREE),
            row(DetectorType.COMPLETABLEFUTURE_CHAIN, "CompletableFutureChainDetector", "CompletableFutureChain", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.EXECUTOR_SHUTDOWN, "ExecutorShutdownDetector", "ExecutorShutdown", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.MUTABLE_MAP_KEY, "MutableMapKeyDetector", "MutableMapKey", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.NESTED_MONITOR_LOCKOUT, "NestedMonitorLockoutDetector", "NestedMonitorLockout", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.LOCK_DOWNGRADE, "LockDowngradeDetector", "LockDowngrade", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.INHERITABLE_THREAD_LOCAL, "InheritableThreadLocalMisuseDetector", "InheritableThreadLocal", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.THREAD_LOCAL_CONTAMINATION, "ThreadLocalContaminationDetector", "ThreadLocalContamination", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.ATOMIC_NON_ATOMIC_UPDATE, "AtomicNonAtomicUpdateDetector", "AtomicNonAtomicUpdate", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, "SynchronizedCollectionIterationDetector", "SynchronizedCollectionIteration", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.SHARED_FORMATTER, "SharedFormatterDetector", "SharedFormatter", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, "ConcurrentMapComputeRecursionDetector", "ConcurrentMapComputeRecursion", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.SYNCHRONIZED_ON_LITERAL, "SynchronizedOnLiteralDetector", "SynchronizedOnLiteral", TrustTier.PROMPT, Evidence.OBSERVED),
            row(DetectorType.PUBLIC_LOCK_EXPOSURE, "PublicLockExposureDetector", "PublicLockExposure", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.FORK_JOIN_TASK_BLOCKING, "ForkJoinTaskBlockingDetector", "ForkJoinTaskBlocking", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.OPTIMISTIC_READ_VALIDATION, "OptimisticReadValidationDetector", "OptimisticReadValidation", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.CF_COMMON_POOL_BLOCKING, "CompletableFutureCommonPoolBlockingDetector", "CfCommonPoolBlocking", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.SHARED_MATCHER, "SharedMatcherDetector", "SharedMatcher", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.SHARED_DECIMAL_FORMAT, "SharedDecimalFormatDetector", "SharedDecimalFormat", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.WEAK_REFERENCE_RACE, "WeakReferenceRaceDetector", "WeakReferenceRace", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.STATEFUL_LAMBDA, "StatefulLambdaDetector", "StatefulLambda", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.SHARED_MESSAGE_DIGEST, "SharedMessageDigestDetector", "SharedMessageDigest", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.INTERRUPT_SWALLOWING, "InterruptSwallowingDetector", "InterruptSwallowing", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.MDC_CONTEXT_LEAK, "MdcContextLeakDetector", "MdcContextLeak", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.SYSTEM_PROPERTY_MUTATION, "SystemPropertyMutationDetector", "SystemPropertyMutation", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.FUTURE_IGNORED, "FutureIgnoredDetector", "FutureIgnored", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.EXPLICIT_GC, "ExplicitGcDetector", "ExplicitGc", TrustTier.PROMPT, Evidence.OBSERVED),
            row(DetectorType.DEPRECATED_THREAD_API, "DeprecatedThreadApiDetector", "DeprecatedThreadApi", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.SHARED_XML_PARSER, "SharedXmlParserDetector", "SharedXmlParser", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.BOXED_PRIMITIVE_LOCK, "BoxedPrimitiveLockDetector", "BoxedPrimitiveLock", TrustTier.PROMPT, Evidence.OBSERVED),
            row(DetectorType.SHARED_TIMEZONE, "SharedTimeZoneDetector", "SharedTimeZone", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.UNCAUGHT_EXCEPTION_HANDLER, "UncaughtExceptionHandlerDetector", "UncaughtExceptionHandler", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.DAEMON_THREAD_HYGIENE, "DaemonThreadHygieneDetector", "DaemonThreadHygiene", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.NOTIFY_WITHOUT_MONITOR, "NotifyWithoutMonitorDetector", "NotifyWithoutMonitor", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.SHARED_SECURE_RANDOM, "SharedSecureRandomDetector", "SharedSecureRandom", TrustTier.ADVISORY, Evidence.CONTEXT_FREE),
            row(DetectorType.WEAK_HASH_MAP_SHARED, "WeakHashMapSharedDetector", "WeakHashMapShared", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.JDBC_CONNECTION_SHARED, "JdbcConnectionSharedDetector", "JdbcConnectionShared", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.SHARED_STATEFUL_CRYPTO, "SharedStatefulCryptoDetector", "SharedStatefulCrypto", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, "NonAtomicConcurrentMapUpdateDetector", "NonAtomicConcurrentMapUpdate", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.SHARED_DEFLATER, "SharedDeflaterDetector", "SharedDeflater", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.THIS_ESCAPE, "ThisEscapeDetector", "ThisEscape", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.THREAD_LOCAL_RANDOM_MISUSE, "ThreadLocalRandomMisuseDetector", "ThreadLocalRandomMisuse", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE, "CompletableFutureObtrudeDetector", "CompletableFutureObtrude", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.SPURIOUS_WAKEUP_HAZARD, "SpuriousWakeupDetector", "SpuriousWakeup", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.LOCK_UPGRADE_DEADLOCK, "LockUpgradeDeadlockDetector", "LockUpgradeDeadlock", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.TRY_LOCK_MISUSE, "TryLockMisuseDetector", "TryLockMisuse", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK, "CompletableFutureBlockingCallbackDetector", "CompletableFutureBlockingCallback", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.STABLE_VALUE_MISUSE, "StableValueMisuseDetector", "StableValueMisuse", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.STRUCTURED_TASK_SCOPE_MISUSE, "StructuredTaskScopeMisuseDetector", "StructuredTaskScopeMisuse", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.GATHERER_CONCURRENCY_MISUSE, "GathererConcurrencyMisuseDetector", "GathererConcurrencyMisuse", TrustTier.PROMPT, Evidence.CONTEXT_FREE),
            row(DetectorType.SHARED_BYTE_BUFFER, "SharedByteBufferDetector", "SharedByteBuffer", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.SHARED_CHARSET_CODER, "SharedCharsetCoderDetector", "SharedCharsetCoder", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.SHARED_CHECKSUM, "SharedChecksumDetector", "SharedChecksum", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.FILE_CHANNEL_POSITION_RACE, "FileChannelPositionRaceDetector", "FileChannelPositionRace", TrustTier.PROMPT, Evidence.CONTEXTUAL),
            row(DetectorType.SHARED_ITERATOR, "SharedIteratorDetector", "SharedIterator", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.HIGH_CONTENTION_ATOMIC, "HighContentionAtomicDetector", "HighContentionAtomic", TrustTier.ADVISORY, Evidence.HEURISTIC),
            row(DetectorType.SHARED_JSON_MAPPER_RECONFIG, "SharedJsonMapperReconfigDetector", "SharedJsonMapperReconfig", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.LAZY_CONSTANT_MISUSE, "LazyConstantMisuseDetector", "LazyConstantMisuse", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.FINAL_FIELD_MUTATION, "FinalFieldMutationDetector", "FinalFieldMutation", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.SHARED_KDF, "SharedKdfDetector", "SharedKdf", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.LATCH_MISUSE, "LatchMisuseDetector", "LatchMisuse", TrustTier.VERDICT, Evidence.OBSERVED),
            row(DetectorType.EXECUTOR_DEADLOCK, "ExecutorDeadlockDetector", "ExecutorDeadlock", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.FUTURE_BLOCKING, "FutureBlockingDetector", "FutureBlocking", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.FLOW_PUBLISHER_CONCURRENCY, "FlowPublisherConcurrencyDetector", "FlowPublisherConcurrency", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.CONFINED_ARENA_THREAD_ESCAPE, "ConfinedArenaThreadEscapeDetector", "ConfinedArenaThreadEscape", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.SHARED_MEMORY_SEGMENT_RACE, "SharedMemorySegmentRaceDetector", "SharedMemorySegmentRace", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE, "VarHandleNonAtomicUpdateDetector", "VarHandleNonAtomicUpdate", TrustTier.PROMPT, Evidence.CONTEXTUAL),
            row(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK, "RecordMutableComponentLeakDetector", "RecordMutableComponentLeak", TrustTier.PROMPT, Evidence.OBSERVED),
            row(DetectorType.STATIC_INIT_DEADLOCK, "StaticInitDeadlockDetector", "StaticInitDeadlock", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.VIRTUAL_THREAD_POOLING, "VirtualThreadPoolingDetector", "VirtualThreadPooling", TrustTier.PROMPT, Evidence.ASSERTED),
            row(DetectorType.PLATFORM_THREAD_PER_TASK, "PlatformThreadPerTaskDetector", "PlatformThreadPerTask", TrustTier.ADVISORY, Evidence.OBSERVED),
            row(DetectorType.SHARED_SPLITTABLE_RANDOM, "SharedSplittableRandomDetector", "SharedSplittableRandom", TrustTier.VERDICT, Evidence.CONTEXTUAL),
            row(DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE, "CompletableFutureCompletionRaceDetector", "CompletableFutureCompletionRace", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION, "CompletableFutureCancellationPropagationDetector", "CompletableFutureCancellationPropagation", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE, "CompletableFutureCombinatorMisuseDetector", "CompletableFutureCombinatorMisuse", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.LAMBDA_LOST_UPDATE, "LambdaLostUpdateDetector", "LambdaLostUpdate", TrustTier.FACT, Evidence.CONTEXTUAL),
            row(DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION, "VirtualThreadResourceSaturationDetector", "VirtualThreadResourceSaturation", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION, "VirtualThreadMonitorSerializationDetector", "VirtualThreadMonitorSerialization", TrustTier.ADVISORY, Evidence.HEURISTIC),
            row(DetectorType.THREAD_LOCAL_CACHE_DEGRADATION, "ThreadLocalCacheDegradationDetector", "ThreadLocalCacheDegradation", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.SCOPE_JOINER_MISUSE, "ScopeJoinerMisuseDetector", "ScopeJoinerMisuse", TrustTier.PROMPT, Evidence.CONTEXT_FREE),
            row(DetectorType.SCOPE_CONFIGURATION_MISUSE, "ScopeConfigurationMisuseDetector", "ScopeConfigurationMisuse", TrustTier.PROMPT, Evidence.HEURISTIC),
            row(DetectorType.SCOPE_RESULT_ESCAPE, "ScopeResultEscapeDetector", "ScopeResultEscape", TrustTier.FACT, Evidence.ASSERTED),
            row(DetectorType.LAZY_COLLECTION_MISUSE, "LazyCollectionMisuseDetector", "LazyCollectionMisuse", TrustTier.PROMPT, Evidence.HEURISTIC)
    );

    private static final List<Row> ROWS = List.copyOf(TABLE.stream().map(Classified::row).toList());
    private static final Map<DetectorType, Row> BY_TYPE = indexByType();
    private static final Map<String, Row> BY_NAME = indexByName();
    private static final Map<DetectorType, Evidence> EVIDENCE = indexEvidence();

    private DetectorTrust() { }

    private static Map<DetectorType, Evidence> indexEvidence() {
        Map<DetectorType, Evidence> out = new EnumMap<>(DetectorType.class);
        for (Classified candidate : TABLE) {
            out.put(candidate.row().type(), candidate.evidence());
        }
        return Map.copyOf(out);
    }

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

    /**
     * {@return what a built-in detector decides its findings from}
     *
     * @param type the detector to look up; {@code null} yields {@link Evidence#HEURISTIC}, whose
     *             cap is the {@link TrustTier#PROMPT} that {@link #tierOf} gives it
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL, since = "1.12.3")
    public static Evidence evidenceOf(DetectorType type) {
        Evidence found = type == null ? null : EVIDENCE.get(type);
        return found == null ? Evidence.HEURISTIC : found;
    }

    /**
     * {@return the highest tier a finding from the named detector may carry}
     *
     * <p>The cap of the detector's {@link Evidence}. A name this table does not know, which is what
     * every third-party detector is, is capped at {@link TrustTier#PROMPT} for the reason
     * {@link #tierOfDetector} resolves it there.
     *
     * @param detectorName the reporting detector's name as it appears in the report map
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL, since = "1.12.3")
    public static TrustTier capOfDetector(String detectorName) {
        Row found = detectorName == null ? null : BY_NAME.get(detectorName);
        return found == null ? TrustTier.PROMPT : evidenceOf(found.type()).cap();
    }

    /**
     * {@return {@code grades} with every tier above the detector's cap lowered to the cap}
     *
     * <p>A graded report names its own tiers, and nothing in the report type stops it from naming
     * one its evidence cannot carry. This is where that stops: the report path applies it before
     * the {@code failOn} gate, the console banner or a listener reads a grade. Returns
     * {@code grades} itself when nothing needed lowering, which is the case for every built-in
     * report the table classifies correctly.
     *
     * @param detectorName the reporting detector's name as it appears in the report map
     * @param grades       the report's grades, in report order
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL, since = "1.12.3")
    public static List<GradedFindings.Grade> clampToCap(String detectorName, List<GradedFindings.Grade> grades) {
        TrustTier cap = capOfDetector(detectorName);
        boolean exceeds = false;
        for (GradedFindings.Grade grade : grades) {
            exceeds |= grade.tier().compareTo(cap) > 0;
        }
        if (!exceeds) {
            return grades;
        }
        List<GradedFindings.Grade> clamped = new ArrayList<>(grades.size());
        for (GradedFindings.Grade grade : grades) {
            clamped.add(grade.tier().compareTo(cap) > 0
                    ? new GradedFindings.Grade(grade.severity(), cap, grade.summary())
                    : grade);
        }
        return List.copyOf(clamped);
    }
}
