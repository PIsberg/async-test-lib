package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.DetectorType;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.Map;
import java.util.Optional;

import static java.util.Map.entry;

/**
 * The severity a detector's findings carry when its own report does not say.
 *
 * <p><strong>Why this exists.</strong> {@link IssueSeverity#fromReport(String)} recovers a
 * finding's severity by matching markers in the report text, and returned {@link
 * IssueSeverity#HIGH} when it found none. 86 of the 142 built-in detectors write no marker, so a
 * merge gate on {@code failOn = HIGH} failed on all of them alike: a spin loop that should yield,
 * an explicit {@code System.gc()} and a lost update were ranked identically, because none of the
 * three said anything and the default said HIGH. That made {@code failOn = HIGH} close to "fail on
 * anything", which is the same as no gate at all.
 *
 * <p>Every one of those detectors now states its severity here, chosen against
 * {@link IssueSeverity}'s own definitions: {@code CRITICAL} where the report's primary claim is
 * that something will not make progress, {@code HIGH} where it claims corruption or an incorrect
 * result, {@code MEDIUM} for degradation and leaks, {@code LOW} for an inefficiency. Where two
 * readings were defensible the higher was taken, because under-ranking a real bug costs more than
 * over-ranking a benign one.
 *
 * <p><strong>A detector's own report always wins.</strong> This table is consulted only when the
 * report marks no severity, so a detector that learns to state one per finding overrides it
 * without touching this file, and its entry then has to be removed:
 * {@code DetectorSeverityMarkerTest} fails on an entry for a detector that marks its own reports,
 * so the table can only shrink as the detectors improve.
 *
 * <p>Third-party detectors arriving through the SPI are not in this table and keep the historical
 * {@code HIGH} default. The library has no basis for ranking somebody else's finding.
 *
 * @since 1.9.7
 */
@AIPublicAPI
@AIKeepInSync(
    mirrors = {"se.deversity.asynctest.DetectorType", "se.deversity.asynctest.diagnostics.DetectorTrust"},
    reason = "An entry here is the severity a built-in detector's findings carry at the failOn gate "
           + "when its report marks none. A detector missing from both this table and the marker "
           + "convention silently falls back to HIGH, which is the defect this table exists to fix.",
    enforcedBy = "se.deversity.asynctest.architecture.DetectorSeverityMarkerTest"
)
@API(status = Status.EXPERIMENTAL)
public final class DetectorDefaultSeverity {

    private static final Map<DetectorType, IssueSeverity> DECLARED = Map.ofEntries(
            entry(DetectorType.VISIBILITY, IssueSeverity.HIGH),
            entry(DetectorType.LIVELOCKS, IssueSeverity.CRITICAL),
            entry(DetectorType.WAKEUP_ISSUES, IssueSeverity.HIGH),
            entry(DetectorType.CONSTRUCTOR_SAFETY, IssueSeverity.HIGH),
            entry(DetectorType.ABA_PROBLEM, IssueSeverity.HIGH),
            entry(DetectorType.LOCK_ORDER, IssueSeverity.CRITICAL),
            entry(DetectorType.SYNCHRONIZERS, IssueSeverity.CRITICAL),
            entry(DetectorType.THREAD_POOL, IssueSeverity.MEDIUM),
            entry(DetectorType.MEMORY_ORDERING, IssueSeverity.HIGH),
            entry(DetectorType.ASYNC_PIPELINE, IssueSeverity.HIGH),
            entry(DetectorType.READ_WRITE_LOCK_FAIRNESS, IssueSeverity.MEDIUM),
            entry(DetectorType.SEMAPHORE, IssueSeverity.HIGH),
            entry(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, IssueSeverity.HIGH),
            entry(DetectorType.CONCURRENT_MODIFICATIONS, IssueSeverity.HIGH),
            entry(DetectorType.LOCK_LEAKS, IssueSeverity.CRITICAL),
            entry(DetectorType.SHARED_RANDOM, IssueSeverity.MEDIUM),
            entry(DetectorType.BLOCKING_QUEUE, IssueSeverity.MEDIUM),
            entry(DetectorType.CONDITION_VARIABLES, IssueSeverity.HIGH),
            entry(DetectorType.SIMPLE_DATE_FORMAT, IssueSeverity.HIGH),
            entry(DetectorType.PARALLEL_STREAMS, IssueSeverity.HIGH),
            entry(DetectorType.RESOURCE_LEAKS, IssueSeverity.MEDIUM),
            entry(DetectorType.COUNTDOWN_LATCH, IssueSeverity.CRITICAL),
            entry(DetectorType.CYCLIC_BARRIER, IssueSeverity.CRITICAL),
            entry(DetectorType.REENTRANT_LOCK, IssueSeverity.HIGH),
            entry(DetectorType.VOLATILE_ARRAY, IssueSeverity.HIGH),
            entry(DetectorType.DOUBLE_CHECKED_LOCKING, IssueSeverity.HIGH),
            entry(DetectorType.WAIT_TIMEOUT, IssueSeverity.CRITICAL),
            entry(DetectorType.LOCK_CONTENTION, IssueSeverity.MEDIUM),
            entry(DetectorType.SYNCHRONIZED_NON_FINAL, IssueSeverity.HIGH),
            entry(DetectorType.MISSED_SIGNAL, IssueSeverity.CRITICAL),
            entry(DetectorType.LAZY_INIT_RACE, IssueSeverity.HIGH),
            entry(DetectorType.PHASER, IssueSeverity.CRITICAL),
            entry(DetectorType.STAMPED_LOCK, IssueSeverity.HIGH),
            entry(DetectorType.EXCHANGER, IssueSeverity.CRITICAL),
            entry(DetectorType.SCHEDULED_EXECUTOR, IssueSeverity.MEDIUM),
            entry(DetectorType.FORK_JOIN_POOL, IssueSeverity.HIGH),
            entry(DetectorType.THREAD_FACTORY, IssueSeverity.HIGH),
            entry(DetectorType.THREAD_LOCAL_LEAKS, IssueSeverity.MEDIUM),
            entry(DetectorType.BUSY_WAITING, IssueSeverity.MEDIUM),
            entry(DetectorType.ATOMICITY_VIOLATIONS, IssueSeverity.HIGH),
            entry(DetectorType.INTERRUPT_MISHANDLING, IssueSeverity.HIGH),
            entry(DetectorType.THREAD_LEAKS, IssueSeverity.MEDIUM),
            entry(DetectorType.SLEEP_IN_LOCK, IssueSeverity.MEDIUM),
            entry(DetectorType.UNBOUNDED_QUEUE, IssueSeverity.MEDIUM),
            entry(DetectorType.THREAD_STARVATION, IssueSeverity.MEDIUM),
            entry(DetectorType.CALENDAR, IssueSeverity.HIGH),
            entry(DetectorType.SHARED_COLLECTIONS, IssueSeverity.HIGH),
            entry(DetectorType.TIMER, IssueSeverity.HIGH),
            entry(DetectorType.COPY_ON_WRITE_COLLECTIONS, IssueSeverity.MEDIUM),
            entry(DetectorType.STRING_BUILDER, IssueSeverity.HIGH),
            entry(DetectorType.HTTP_CLIENT, IssueSeverity.HIGH),
            entry(DetectorType.STREAM_CLOSING, IssueSeverity.MEDIUM),
            entry(DetectorType.CACHE_CONCURRENCY, IssueSeverity.HIGH),
            entry(DetectorType.COMPLETABLEFUTURE_CHAIN, IssueSeverity.HIGH),
            entry(DetectorType.EXECUTOR_SHUTDOWN, IssueSeverity.MEDIUM),
            entry(DetectorType.MUTABLE_MAP_KEY, IssueSeverity.HIGH),
            entry(DetectorType.NESTED_MONITOR_LOCKOUT, IssueSeverity.CRITICAL),
            entry(DetectorType.LOCK_DOWNGRADE, IssueSeverity.HIGH),
            entry(DetectorType.INHERITABLE_THREAD_LOCAL, IssueSeverity.HIGH),
            entry(DetectorType.THREAD_LOCAL_CONTAMINATION, IssueSeverity.HIGH),
            entry(DetectorType.ATOMIC_NON_ATOMIC_UPDATE, IssueSeverity.HIGH),
            entry(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, IssueSeverity.HIGH),
            entry(DetectorType.SHARED_FORMATTER, IssueSeverity.HIGH),
            entry(DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, IssueSeverity.HIGH),
            entry(DetectorType.SYNCHRONIZED_ON_LITERAL, IssueSeverity.HIGH),
            entry(DetectorType.PUBLIC_LOCK_EXPOSURE, IssueSeverity.HIGH),
            entry(DetectorType.FORK_JOIN_TASK_BLOCKING, IssueSeverity.MEDIUM),
            entry(DetectorType.OPTIMISTIC_READ_VALIDATION, IssueSeverity.HIGH),
            entry(DetectorType.CF_COMMON_POOL_BLOCKING, IssueSeverity.MEDIUM),
            entry(DetectorType.SHARED_MATCHER, IssueSeverity.HIGH),
            entry(DetectorType.SHARED_DECIMAL_FORMAT, IssueSeverity.HIGH),
            entry(DetectorType.WEAK_REFERENCE_RACE, IssueSeverity.HIGH),
            entry(DetectorType.STATEFUL_LAMBDA, IssueSeverity.HIGH),
            entry(DetectorType.INTERRUPT_SWALLOWING, IssueSeverity.HIGH),
            entry(DetectorType.MDC_CONTEXT_LEAK, IssueSeverity.HIGH),
            entry(DetectorType.SYSTEM_PROPERTY_MUTATION, IssueSeverity.HIGH),
            entry(DetectorType.FUTURE_IGNORED, IssueSeverity.HIGH),
            entry(DetectorType.EXPLICIT_GC, IssueSeverity.LOW),
            entry(DetectorType.DEPRECATED_THREAD_API, IssueSeverity.HIGH),
            entry(DetectorType.SHARED_XML_PARSER, IssueSeverity.HIGH),
            entry(DetectorType.BOXED_PRIMITIVE_LOCK, IssueSeverity.HIGH),
            entry(DetectorType.SHARED_TIMEZONE, IssueSeverity.HIGH),
            entry(DetectorType.UNCAUGHT_EXCEPTION_HANDLER, IssueSeverity.HIGH),
            entry(DetectorType.LATCH_MISUSE, IssueSeverity.CRITICAL),
            entry(DetectorType.EXECUTOR_DEADLOCK, IssueSeverity.CRITICAL),
            entry(DetectorType.FUTURE_BLOCKING, IssueSeverity.CRITICAL)
    );

    private DetectorDefaultSeverity() { }

    /**
     * {@return the declared severity for a built-in detector that marks none itself, if any}
     *
     * @param type the detector; {@code null} yields empty
     */
    public static Optional<IssueSeverity> of(DetectorType type) {
        return type == null ? Optional.empty() : Optional.ofNullable(DECLARED.get(type));
    }

    /**
     * {@return the severity a finding should be gated on}
     *
     * <p>The one place that answer is computed, so the {@code failOn} gate, the JSON report and
     * the SARIF output cannot disagree about what a finding was worth. Precedence: what the report
     * marks, then what the detector declares here, then {@link IssueSeverity#HIGH} for anything
     * this library does not know, which is every third-party detector.
     *
     * @param detectorName the reporting detector's name, as it appears in the report map
     * @param report       the report text
     */
    public static IssueSeverity of(String detectorName, String report) {
        return IssueSeverity.markedIn(report)
                .or(() -> DetectorTrust.typeOfDetector(detectorName).flatMap(DetectorDefaultSeverity::of))
                .orElse(IssueSeverity.HIGH);
    }
}
