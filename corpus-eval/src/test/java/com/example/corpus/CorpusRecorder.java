package com.example.corpus;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.SiteCapture;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects every finding the detectors report and attributes it to the subject being exercised.
 *
 * <p>The library's listener events carry no test identity, so {@link SubjectTracking} sets the
 * current subject around each test method and this recorder stamps it onto the violation. Tests
 * run sequentially in this module, which is what makes a single current-subject field correct.
 */
final class CorpusRecorder implements AsyncTestListener {

    /**
     * One reported finding, already resolved to the reporting detector's trust tier.
     *
     * <p>{@code evidence} flattens the violation's sites and attributes into one line. A finding
     * on a documented-thread-safe subject has to be arguable from the report alone, and the
     * headline message is the same sentence for every atomicity finding; the field and the class
     * are in the evidence.
     *
     * <p>{@code gradedTier} is the strongest tier among the report's graded findings, as the runner
     * clamped them before its gate read them (the violation's {@code findingTiers}, #837), or
     * {@code null} for a report that grades nothing.
     */
    record Finding(String subject, String detector, IssueSeverity severity, TrustTier tier,
                   String message, String evidence, @Nullable TrustTier gradedTier) {

        /** A finding from a report that carried no grades. */
        Finding(String subject, String detector, IssueSeverity severity, TrustTier tier,
                String message, String evidence) {
            this(subject, detector, severity, tier, message, evidence, null);
        }
    }

    /** A RuntimeException thrown out of a subject's own code while several threads used it. */
    record Crash(String subject, String exception) {
    }

    private static final CorpusRecorder INSTANCE = new CorpusRecorder();
    private static final List<Finding> FINDINGS = new CopyOnWriteArrayList<>();
    private static final List<Crash> CRASHES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger BODY_EXECUTIONS = new AtomicInteger();

    private static final Map<String, Long> EVENTS_PER_SUBJECT = new ConcurrentHashMap<>();

    /** What a subject noted about its own run, printed if it fails its outcome (#1133). */
    private static final Map<String, String> EVIDENCE_PER_SUBJECT = new ConcurrentHashMap<>();

    /** Per subject: how many polls took an object another worker offered. */
    private static final Map<String, AtomicInteger> CROSS_WORKER_TAKES = new ConcurrentHashMap<>();

    /** Per subject: the most takes the run's AtomicityValidator had recorded when a body asked. */
    private static final Map<String, AtomicInteger> VALIDATOR_TAKES = new ConcurrentHashMap<>();

    private static volatile String currentSubject = "unattributed";
    private static volatile long subjectStartEvents;

    private CorpusRecorder() {
    }

    static void install() {
        AsyncTestListenerRegistry.register(INSTANCE);
    }

    static void uninstall() {
        AsyncTestListenerRegistry.unregister(INSTANCE);
    }

    static void currentSubject(String subject) {
        currentSubject = subject;
    }

    static void recordCrash(RuntimeException thrown) {
        CRASHES.add(new Crash(currentSubject, thrown.getClass().getName()));
    }

    static void countBodyExecution() {
        BODY_EXECUTIONS.incrementAndGet();
    }

    /** Remembers where the telemetry counter stood as a subject started. */
    static void markSubjectStart() {
        subjectStartEvents = TelemetryRegistry.publishedEvents();
    }

    /**
     * Attributes the events published while {@code subject} ran to that subject.
     *
     * <p>The number a zero finding count needs. Without it, a subject that produced no finding and
     * a subject the agent never observed are the same row, which is the per-subject form of the
     * exposure problem the report already fixes per detector.
     *
     * @param subject the test method that just finished
     */
    static void markSubjectEnd(String subject) {
        EVENTS_PER_SUBJECT.put(subject, TelemetryRegistry.publishedEvents() - subjectStartEvents);
    }

    /**
     * Notes what a hand-off body saw, so a silent MUST_FIRE row can say which side lost it (#1133).
     *
     * <p>{@code agent_jctoolsHandOff_offererWritesAfterTheOffer} went silent once in about 100
     * legs with nothing kept. Two counts separate the causes: how many polls took another worker's
     * object (zero: no hand-off happened, the premise failed), and how many takes the run's
     * AtomicityValidator had recorded (zero with hand-offs: the takes never reached it, a feed
     * problem; more: they arrived and were excused, a validator problem). The validator count
     * covers the rounds drained before this call, which the runner flushes before each round
     * starts, so every round but the last.
     *
     * <p>Reads the validator and records nothing, which is why it lives here and not in the lane,
     * where {@link AgentRowPremise} refuses any mention of {@code AsyncTestContext}. Corpus
     * bookkeeping like {@link #countBodyExecution}, not a detector feed.
     *
     * @param tookAnotherWorkersObject whether this body's poll returned an object it did not offer
     */
    static void noteHandOff(boolean tookAnotherWorkersObject) {
        String subject = currentSubject;
        AtomicInteger cross = CROSS_WORKER_TAKES.computeIfAbsent(subject, ignored -> new AtomicInteger());
        if (tookAnotherWorkersObject) {
            cross.incrementAndGet();
        }
        AtomicInteger seen = VALIDATOR_TAKES.computeIfAbsent(subject, ignored -> new AtomicInteger());
        AtomicityValidator validator = AsyncTestContext.atomicityValidator();
        if (validator != null) {
            seen.accumulateAndGet(validator.ownershipTakes(), Math::max);
        }
    }

    /**
     * Sets, or with {@code null} clears, the evidence printed for {@code subject} if it fails.
     *
     * @param subject  the test method
     * @param evidence {@code key=value} pairs, or {@code null} to forget them
     */
    static void noteEvidence(String subject, @Nullable String evidence) {
        if (evidence == null) {
            EVIDENCE_PER_SUBJECT.remove(subject);
        } else {
            EVIDENCE_PER_SUBJECT.put(subject, evidence);
        }
    }

    /** {@return what {@code subject} noted about its run, or an empty string} */
    static String evidenceFor(String subject) {
        String noted = EVIDENCE_PER_SUBJECT.get(subject);
        if (noted != null) {
            return noted;
        }
        AtomicInteger cross = CROSS_WORKER_TAKES.get(subject);
        AtomicInteger seen = VALIDATOR_TAKES.get(subject);
        return cross == null || seen == null ? ""
                : "crossWorkerTakes=" + cross.get() + " validatorTakesBeforeLastRound=" + seen.get();
    }

    /** {@return how many access events were published while {@code subject} ran} */
    static long eventsFor(String subject) {
        return EVENTS_PER_SUBJECT.getOrDefault(subject, 0L);
    }

    static List<Finding> findings() {
        return List.copyOf(FINDINGS);
    }

    static List<Crash> crashes() {
        return List.copyOf(CRASHES);
    }

    static int bodyExecutions() {
        return BODY_EXECUTIONS.get();
    }

    @Override
    public void onViolation(Violation violation) {
        FINDINGS.add(new Finding(
                currentSubject,
                violation.detector(),
                violation.severity(),
                DetectorTrust.tierOfDetector(violation.detector()),
                violation.message(),
                evidenceOf(violation),
                strongestFindingTier(violation.attributes())));
    }

    /**
     * {@return the strongest tier named in a violation's {@code findingTiers} attribute, or
     * {@code null} when it has none because its report graded nothing}
     *
     * @param attributes the violation's attributes
     */
    static @Nullable TrustTier strongestFindingTier(Map<String, Object> attributes) {
        Object tiers = attributes.get("findingTiers");
        if (tiers == null) {
            return null;
        }
        TrustTier strongest = null;
        for (String tier : tiers.toString().split(",")) {
            TrustTier read = TrustTier.valueOf(tier.trim());
            strongest = strongest == null || read.compareTo(strongest) > 0 ? read : strongest;
        }
        return strongest;
    }

    /** {@return the violation's sites and attributes on one line, or {@code "-"} when it has none} */
    private static String evidenceOf(Violation violation) {
        StringBuilder evidence = new StringBuilder();
        for (SiteCapture.Site site : violation.sites()) {
            evidence.append(evidence.isEmpty() ? "" : "; ").append(site);
        }
        for (Map.Entry<String, Object> attribute : violation.attributes().entrySet()) {
            evidence.append(evidence.isEmpty() ? "" : "; ")
                    .append(attribute.getKey()).append('=').append(attribute.getValue());
        }
        return evidence.isEmpty() ? "-" : evidence.toString();
    }
}
