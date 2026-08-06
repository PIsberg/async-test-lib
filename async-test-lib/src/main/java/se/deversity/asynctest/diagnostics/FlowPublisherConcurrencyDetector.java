package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Detects reactive-streams contract violations on {@code java.util.concurrent.Flow}
 * subscribers: overlapping {@code onNext} delivery, signals after a terminal signal,
 * and deliveries exceeding recorded demand.
 *
 * <p>The Flow API inherits the reactive-streams specification: signals to a
 * {@code Subscriber} must be serialized (rule 1.3), at most one terminal signal may be
 * delivered and nothing after it (rule 1.7), and a publisher must not deliver more
 * {@code onNext} signals than the subscriber has requested (rule 1.1). A hand-rolled
 * {@code Publisher} that fans work out to an executor breaks rule 1.3 first: two threads
 * inside {@code onNext} at once corrupt any non-thread-safe subscriber state, exactly the
 * class of bug {@code SubmissionPublisher}'s per-subscriber serialization exists to
 * prevent.
 *
 * <p>Overlap is observed, not inferred: {@link #recordNextStart} and
 * {@link #recordNextEnd} bracket each delivery, and the high-water mark of concurrent
 * in-flight deliveries per subscriber is the finding. Demand violations are reported at
 * MEDIUM with conditional wording, because the detector can only compare recorded
 * deliveries against recorded requests — an unrecorded {@code request()} call would look
 * like an overrun. No demand finding is emitted when no request was ever recorded.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new FlowPublisherConcurrencyDetector();
 * d.recordSubscribe(subscriber, "priceFeed", Thread.currentThread());
 * d.recordRequest(subscriber, 1);
 * d.recordNextStart(subscriber, Thread.currentThread());
 * // ... subscriber.onNext(item) ...
 * d.recordNextEnd(subscriber);
 * d.recordComplete(subscriber, Thread.currentThread());
 * }</pre>
 *
 * @since 1.7.1
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; "
        + "thread-id/name sets are ConcurrentHashMap.newKeySet(); counters are LongAdder / "
        + "AtomicInteger with a CAS high-water mark.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/FlowPublisherConcurrencyDetectorTest.java"
)
public final class FlowPublisherConcurrencyDetector {

    private static final class State {
        final String label;
        final Set<Long>   threadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        final AtomicInteger inOnNext            = new AtomicInteger();
        final AtomicInteger maxConcurrentOnNext = new AtomicInteger();
        final AtomicBoolean terminated          = new AtomicBoolean();
        final AtomicBoolean demandRecorded      = new AtomicBoolean();
        final LongAdder signalsAfterTerminal = new LongAdder();
        final LongAdder requested            = new LongAdder();
        final LongAdder delivered            = new LongAdder();
        State(String label) { this.label = label; }
    }

    private final Map<Integer, State> subscribers = new ConcurrentHashMap<>();

    /**
     * Record a subscription. Registers the subscriber under {@code label} so later
     * findings read as the test author named them.
     *
     * @param subscriber the subscriber instance (null-safe)
     * @param label      human-readable label for triage (may be {@code null})
     * @param thread     the thread delivering {@code onSubscribe}
     */
    public void recordSubscribe(@Nullable Object subscriber, @Nullable String label, @Nullable Thread thread) {
        State s = stateFor(subscriber, label);
        if (s == null || thread == null) return;
        s.threadIds.add(thread.threadId());
        s.threadNames.add(thread.getName());
    }

    /**
     * Record a {@code Subscription.request(n)} call. Once any demand has been recorded,
     * {@link #analyze()} compares total deliveries against total demand.
     *
     * @param subscriber the subscriber whose subscription requested (null-safe)
     * @param n          the requested amount
     */
    public void recordRequest(@Nullable Object subscriber, long n) {
        State s = stateFor(subscriber, null);
        if (s == null) return;
        s.demandRecorded.set(true);
        s.requested.add(n);
    }

    /**
     * Record entry into {@code onNext}. Pair with {@link #recordNextEnd}; the bracket is
     * what makes overlapping delivery observable rather than inferred.
     *
     * @param subscriber the subscriber receiving the signal (null-safe)
     * @param thread     the delivering thread
     */
    public void recordNextStart(@Nullable Object subscriber, @Nullable Thread thread) {
        State s = stateFor(subscriber, null);
        if (s == null || thread == null) return;
        s.threadIds.add(thread.threadId());
        s.threadNames.add(thread.getName());
        s.delivered.increment();
        int inFlight = s.inOnNext.incrementAndGet();
        s.maxConcurrentOnNext.accumulateAndGet(inFlight, Math::max);
        if (s.terminated.get()) s.signalsAfterTerminal.increment();
    }

    /**
     * Record exit from {@code onNext}. Unbalanced calls floor at zero rather than going
     * negative, so a missing bracket cannot manufacture an overlap.
     *
     * @param subscriber the subscriber whose delivery completed (null-safe)
     */
    public void recordNextEnd(@Nullable Object subscriber) {
        State s = stateFor(subscriber, null);
        if (s == null) return;
        s.inOnNext.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    /**
     * Record a terminal {@code onComplete}. A second terminal signal, or any signal after
     * this one, counts against rule 1.7.
     *
     * @param subscriber the subscriber completing (null-safe)
     * @param thread     the delivering thread
     */
    public void recordComplete(@Nullable Object subscriber, @Nullable Thread thread) {
        recordTerminal(subscriber, thread);
    }

    /**
     * Record a terminal {@code onError}. A second terminal signal, or any signal after
     * this one, counts against rule 1.7.
     *
     * @param subscriber the subscriber erroring (null-safe)
     * @param thread     the delivering thread
     */
    public void recordError(@Nullable Object subscriber, @Nullable Thread thread) {
        recordTerminal(subscriber, thread);
    }

    private void recordTerminal(@Nullable Object subscriber, @Nullable Thread thread) {
        State s = stateFor(subscriber, null);
        if (s == null) return;
        if (thread != null) {
            s.threadIds.add(thread.threadId());
            s.threadNames.add(thread.getName());
        }
        if (!s.terminated.compareAndSet(false, true)) s.signalsAfterTerminal.increment();
    }

    private @Nullable State stateFor(@Nullable Object subscriber, @Nullable String label) {
        if (subscriber == null) return null;
        int id = System.identityHashCode(subscriber);
        State s = subscribers.get(id);
        if (s == null) {
            final String lbl = label != null
                    ? label
                    : subscriber.getClass().getSimpleName() + "@" + id;
            s = subscribers.computeIfAbsent(id, k -> new State(lbl));
        }
        return s;
    }

    /**
     * Evaluate the observed state and produce a report. Must be idempotent:
     * calling it N times on quiescent state yields N identical reports.
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : subscribers.values()) {
            int overlap = s.maxConcurrentOnNext.get();
            if (overlap > 1) {
                add(r, s, IssueSeverity.HIGH, String.format(
                        "HIGH: '%s' had %d overlapping onNext deliveries (threads: %s) — the "
                        + "reactive-streams contract requires signals to a Subscriber to be "
                        + "serialized (rule 1.3); concurrent onNext corrupts any non-thread-safe "
                        + "subscriber state.",
                        s.label, overlap, String.join(", ", s.threadNames)));
            }
            long late = s.signalsAfterTerminal.sum();
            if (late > 0) {
                add(r, s, IssueSeverity.HIGH, String.format(
                        "HIGH: '%s' received %d signal(s) after a terminal onComplete/onError — "
                        + "rule 1.7 allows at most one terminal signal and nothing after it.",
                        s.label, late));
            }
            long delivered = s.delivered.sum();
            long requested = s.requested.sum();
            if (s.demandRecorded.get() && delivered > requested) {
                add(r, s, IssueSeverity.MEDIUM, String.format(
                        "MEDIUM: '%s' was delivered %d onNext signal(s) with only %d requested — "
                        + "if every request() was recorded, the publisher exceeded demand "
                        + "(rule 1.1). The detector compares recorded signals only; verify no "
                        + "request() call went unrecorded before acting.",
                        s.label, delivered, requested));
            }
        }
        return r;
    }

    private static void add(Report r, State s, IssueSeverity severity, String msg) {
        r.violations.add(msg);
        r.structuredViolations.add(new Violation(
                "FlowPublisherConcurrency",
                severity,
                msg,
                List.of(),
                Map.of(
                        "label", s.label,
                        "threadCount", s.threadIds.size()),
                Instant.now()));
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report {
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "FlowPublisherConcurrency — clean";
            StringBuilder sb = new StringBuilder("FlowPublisherConcurrency DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Serialize subscriber signals (rule 1.3): deliver from one thread, ")
              .append("or publish via SubmissionPublisher, which serializes per subscriber.\n")
              .append("    - Never signal after onComplete/onError (rule 1.7).\n")
              .append("    - Deliver at most the total demand from request(n) (rule 1.1).\n");
            return sb.toString();
        }
    }
}
