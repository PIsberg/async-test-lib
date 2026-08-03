package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Advisory detector for hot compare-and-swap loops on shared
 * {@code AtomicLong}/{@code AtomicInteger}/{@code AtomicReference} instances that
 * would perform better as {@code LongAdder}/{@code LongAccumulator}.
 *
 * <p><strong>Why it matters.</strong> A single {@code Atomic*} field backs one CAS
 * loop: every writer spins on {@code compareAndSet} against the same memory
 * location, so under heavy multi-threaded contention the failure rate climbs and
 * throughput collapses as threads repeatedly retry against a value that keeps
 * changing underneath them. {@code LongAdder}/{@code LongAccumulator} avoid this by
 * striping the counter across internal cells, letting concurrent updates land on
 * different cells and only summing them on read — dramatically reducing CAS
 * failures for accumulate-style workloads (counters, statistics, sums).
 *
 * <p>This is <em>not</em> a correctness bug — {@code AtomicLong} remains perfectly
 * correct under contention, just slower than it needs to be. Keep {@code AtomicLong}
 * when the code needs the exact current value via a read-modify-write (e.g.
 * {@code compareAndSet} gating on a specific value, or {@code getAndUpdate} whose
 * result is consumed immediately); switch to {@code LongAdder}/{@code LongAccumulator}
 * when the field is a pure statistic or counter that is only summed occasionally.
 *
 * <p>Cooperative API: report every CAS attempt via {@link #recordCasAttempt} and
 * every {@code incrementAndGet()}-style update via {@link #recordUpdate}. A finding
 * is raised for an atomic instance when, over the observed window, at least two
 * distinct threads contended for it, the total attempt count reached the
 * configured threshold, and at least 10% of the CAS attempts failed.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new HighContentionAtomicDetector();
 * long prev;
 * boolean ok;
 * do {
 *     prev = counter.get();
 *     ok = counter.compareAndSet(prev, prev + 1);
 *     d.recordCasAttempt(counter, ok);
 * } while (!ok);
 *
 * // or, for a plain incrementAndGet():
 * counter.incrementAndGet();
 * d.recordUpdate(counter);
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; counters are LongAdder; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/HighContentionAtomicDetectorTest.java"
)
public final class HighContentionAtomicDetector {

    /** Default total-attempt count above which an instance becomes eligible for a finding. */
    public static final long DEFAULT_ATTEMPT_THRESHOLD = 1000L;

    /** Minimum failed-CAS ratio (failures / total attempts) required to raise a finding. */
    private static final double FAILURE_RATIO_THRESHOLD = 0.10;

    /** Minimum number of distinct contending threads required to raise a finding. */
    private static final int MIN_DISTINCT_THREADS = 2;

    private static final class State {
        final String label;
        final LongAdder totalAttempts  = new LongAdder();
        final LongAdder failedAttempts = new LongAdder();
        final LongAdder updateCount    = new LongAdder();
        final Set<Long>   threadIds    = ConcurrentHashMap.newKeySet();
        final Set<String> threadNames  = ConcurrentHashMap.newKeySet();

        State(String label) {
            this.label = label;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();
    private final long attemptThreshold;

    /** Creates a detector using {@link #DEFAULT_ATTEMPT_THRESHOLD} as the attempt threshold. */
    public HighContentionAtomicDetector() {
        this(DEFAULT_ATTEMPT_THRESHOLD);
    }

    /**
     * Creates a detector with a custom attempt threshold.
     *
     * @param attemptThreshold minimum total attempts (CAS + updates) an instance must
     *                         accumulate before it is eligible for a finding
     */
    public HighContentionAtomicDetector(long attemptThreshold) {
        this.attemptThreshold = attemptThreshold;
    }

    /**
     * Records one compare-and-swap attempt against {@code atomic}, made by the calling thread.
     *
     * @param atomic    the {@code AtomicLong}/{@code AtomicInteger}/{@code AtomicReference} (null-safe)
     * @param succeeded whether the CAS attempt succeeded
     */
    public void recordCasAttempt(Object atomic, boolean succeeded) {
        if (atomic == null) return;
        State s = stateFor(atomic);
        s.totalAttempts.increment();
        if (!succeeded) {
            s.failedAttempts.increment();
        }
        track(s, Thread.currentThread());
    }

    /**
     * Records one {@code incrementAndGet()}-style update against {@code atomic}, made by the
     * calling thread. Counted as a single, always-successful attempt for contention purposes,
     * but tracked separately so reports can distinguish CAS-loop churn from plain updates.
     *
     * @param atomic the {@code AtomicLong}/{@code AtomicInteger}/{@code AtomicReference} (null-safe)
     */
    public void recordUpdate(Object atomic) {
        if (atomic == null) return;
        State s = stateFor(atomic);
        s.totalAttempts.increment();
        s.updateCount.increment();
        track(s, Thread.currentThread());
    }

    private State stateFor(Object atomic) {
        int id = System.identityHashCode(atomic);
        State existing = instances.get(id);
        if (existing != null) return existing;
        return instances.computeIfAbsent(id,
                k -> new State(atomic.getClass().getSimpleName() + "@" + k));
    }

    private static void track(State s, Thread thread) {
        s.threadIds.add(thread.threadId());
        s.threadNames.add(thread.getName());
    }

    /**
     * Analyses recorded data and returns a high-contention advisory report.
     *
     * @return the report; empty when no instance meets all three trigger conditions
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            int threadCount = s.threadIds.size();
            long attempts = s.totalAttempts.sum();
            long failures = s.failedAttempts.sum();
            if (threadCount < MIN_DISTINCT_THREADS) continue;
            if (attempts < attemptThreshold) continue;
            double failureRatio = (attempts == 0) ? 0.0 : (double) failures / attempts;
            if (failureRatio < FAILURE_RATIO_THRESHOLD) continue;

            String msg = String.format(Locale.ROOT,
                    "%s: %d attempt(s) from %d threads, %d failed CAS (%.1f%% failure ratio) — "
                            + "high contention on a shared Atomic* field; consider LongAdder/LongAccumulator "
                            + "for statistics/counters, keep AtomicLong when the exact-value "
                            + "read-modify-write semantics are required.",
                    s.label, attempts, threadCount, failures, failureRatio * 100.0);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "HighContentionAtomic",
                    IssueSeverity.LOW,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "attempts", attempts,
                            "failures", failures,
                            "failureRatio", failureRatio,
                            "threadCount", threadCount),
                    Instant.now()));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** The violations. */
        public final List<String> violations = new ArrayList<>();
        /** The structured violations. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "HIGH CONTENTION ATOMIC — clean";
            StringBuilder sb = new StringBuilder("HIGH CONTENTION ATOMIC (ADVISORY):\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - For pure counters/statistics, replace AtomicLong/AtomicInteger with LongAdder,\n")
              .append("      or LongAccumulator for non-sum reductions; sum() only on read.\n")
              .append("    - Keep AtomicLong/AtomicInteger/AtomicReference when the code needs the exact\n")
              .append("      current value as part of a read-modify-write (e.g. CAS gated on a specific value).\n");
            return sb.toString();
        }
    }
}
