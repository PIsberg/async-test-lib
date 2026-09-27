package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

/**
 * Detects non-atomic check-then-act compound operations on a {@link ConcurrentMap}.
 *
 * <p><strong>Why it matters.</strong> A {@link ConcurrentMap} makes each
 * <em>individual</em> operation atomic, which lulls callers into writing
 * compound sequences that are <em>not</em>:
 *
 * <pre>{@code
 * if (!map.containsKey(k)) {   // thread A and B both see "absent"
 *     map.put(k, compute());   // ...both put; one result is lost
 * }
 * }</pre>
 *
 * <p>or the read-modify-write variant:
 *
 * <pre>{@code
 * Value v = map.get(k);        // both read the same snapshot
 * map.put(k, v.withIncrement); // last writer wins; updates vanish
 * }</pre>
 *
 * <p>The thread-safe primitive that makes the operation atomic is
 * {@code putIfAbsent}, {@code computeIfAbsent}, {@code compute}, or {@code merge}.
 * This is distinct from {@link AtomicNonAtomicUpdateDetector} (which covers
 * {@code Atomic*} types) and from {@link ConcurrentMapComputeRecursionDetector}
 * (which covers re-entrancy <em>inside</em> {@code computeIfAbsent}).
 *
 * <p>Because the bug is a usage pattern rather than an object property, this
 * detector is cooperative: the code under test reports each non-atomic
 * check-then-act it performs via {@link #recordCheckThenAct}. The detector flags
 * a violation when two or more threads perform such a sequence against the same
 * map and key — the exact precondition for a lost update — and no one lock covered
 * every one of them. A compound operation inside a critical section every caller enters
 * is atomic in effect, so it is not reported. The map's own monitor counts with no
 * declaration ({@code synchronized (map)}); any other lock is seen only when declared
 * through {@code AsyncTestContext.holdingLock(...)} or woven by the agent, and a lock
 * the library never saw leaves the finding standing.
 *
 * <p>Callers that all put the very same instance lose nothing: the map ends as
 * {@code putIfAbsent} would leave it, and no caller holds a value the map dropped. A caller
 * that says what it put, through the overload taking the value, lets the detector excuse such
 * a site. The excuse is judged per round, as the sharing is: callers that each put their
 * round's one instance are not reported for putting a different one in another round, which
 * they never overlapped, while a round whose racing callers put two instances, or one of which
 * recorded no value, is. Values are compared by identity, not {@code equals}: two new empty
 * lists are equal, yet the one the map dropped loses whatever its caller adds to it next. So
 * equal immutable values built per caller, such as {@code "v" + i} or a {@code Long} outside the
 * boxing cache, are still reported; a caller that means one value passes one canonical instance.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new NonAtomicConcurrentMapUpdateDetector();
 * // inside the code path that does get/containsKey-then-put:
 * d.recordCheckThenAct(cache, userId, "lazy-cache-fill", Thread.currentThread());
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per (map,key) state in a ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/NonAtomicConcurrentMapUpdateDetectorTest.java"
)
public final class NonAtomicConcurrentMapUpdateDetector {

    /** One (map, key) site: its labels, the round in progress, and the round that lost a put. */
    private static final class State {
        final String mapLabel;
        final String key;
        final String operation;

        /** The round in progress, replaced by the first caller of a later round. */
        private final AtomicReference<@Nullable RoundState> current = new AtomicReference<>();

        /** The first round whose callers raced and did not all put one instance; kept for the report. */
        private final AtomicReference<@Nullable RoundState> finding = new AtomicReference<>();

        State(String mapLabel, String key, String operation) {
            this.mapLabel = mapLabel;
            this.key = key;
            this.operation = operation;
        }

        /**
         * {@return the state of round {@code number}, started by the first caller in it}
         *
         * <p>A caller still recording from an older round joins the newer one, the direction that
         * can only add a finding, as {@link SelfGuard.RoundThreads} does. Only the round in
         * progress and the round a finding came from are kept, so memory does not grow with the
         * rounds.
         */
        RoundState roundFor(int number) {
            RoundState round = current.get();
            while (round == null || round.number < number) {
                RoundState next = new RoundState(number);
                if (current.compareAndSet(round, next)) {
                    return next;
                }
                round = current.get();
            }
            return round;
        }

        /** Keeps {@code round} as the finding when it raced and lost a put; the first one wins. */
        void judge(RoundState round) {
            if (finding.get() == null && round.sharedAndUnguarded() && !round.oneInstancePut()) {
                finding.compareAndSet(null, round);
            }
        }
    }

    /**
     * One round's check-then-acts on a site. The sharing verdict and the same-instance excuse are
     * both taken within the round (#833): rounds run one after another, so callers of different
     * rounds never overlapped, and what one round put says nothing about another round's race.
     */
    private static final class RoundState extends SelfGuard.ThreadTrackedInstance {
        /** Stands for a round whose callers put different instances, or where one said nothing. */
        private static final Object MIXED = new Object();

        final int number;

        /**
         * The one instance every caller of this round so far put, {@link #MIXED} once two differed
         * or one recorded no value, {@code null} before the first call. It only moves forward, from
         * {@code null} to one instance to {@code MIXED}, so a round once unexcused stays reported.
         */
        private final AtomicReference<@Nullable Object> putValue = new AtomicReference<>();

        RoundState(int number) {
            this.number = number;
        }

        /** Folds one caller's put into {@link #putValue}; a {@code null} value makes the round {@code MIXED}. */
        @SuppressWarnings("ReferenceEquality") // equal but distinct values are two puts (#827)
        void notePut(@Nullable Object value) {
            Object seen = putValue.get();
            while (true) {
                Object next = value == null || (seen != null && seen != value) ? MIXED : value; // NOPMD CompareObjectsWithEquals - one instance, by identity (#827)
                if (next == seen || putValue.compareAndSet(seen, next)) { // NOPMD CompareObjectsWithEquals - unchanged, nothing to publish
                    return;
                }
                seen = putValue.get();
            }
        }

        /** {@return whether every caller of this round said it put one and the same instance} */
        @SuppressWarnings("ReferenceEquality") // MIXED is a sentinel
        boolean oneInstancePut() {
            Object seen = putValue.get();
            return seen != null && seen != MIXED; // NOPMD CompareObjectsWithEquals - sentinel
        }
    }

    /**
     * One check-then-act site: the map by identity, the key by the key's own equality, which is
     * how the map itself tells keys apart. Both used to be folded into a string, the map as its
     * identity hash and the key as {@code String.valueOf(key)}, so two maps whose hashes collided
     * shared sites, and {@code 1} and {@code "1"} - two keys of one map - read as one key reached
     * by two threads.
     */
    private record Site(IdentityKey map, @Nullable Object key) { }

    private final Map<Site, State> sites = new ConcurrentHashMap<>();

    /**
     * Record that the calling thread performed a non-atomic check-then-act
     * (e.g. {@code containsKey}/{@code get} then {@code put}) against
     * {@code map} for {@code key}. The detector is not told what was put, so a site recorded
     * here is reported whatever every caller put; see
     * {@link #recordCheckThenAct(ConcurrentMap, Object, Object, String, Thread)}.
     *
     * @param map       the ConcurrentMap being mutated (null-safe)
     * @param key       the key involved (may be {@code null})
     * @param operation descriptive label for reports (may be {@code null})
     * @param thread    the thread performing the sequence
     */
    public void recordCheckThenAct(ConcurrentMap<?, ?> map, Object key, String operation, Thread thread) {
        recordCheckThenAct(map, key, null, operation, thread);
    }

    /**
     * Record a {@code containsKey}-then-{@code put} that puts {@code value} when {@code key} is
     * absent. When every caller of a round put the same instance, that round's race lost nothing
     * and is not reported.
     *
     * <p>Only for a value chosen without reading the map. After {@code v = map.get(k);
     * map.put(k, v + 1)} two callers putting one value is the lost update itself, and a check
     * that also decides other work, such as sending once, is a defect whatever is put: record
     * those with {@link #recordCheckThenAct(ConcurrentMap, Object, String, Thread)}. The detector
     * cannot tell those shapes from the absent-check one: it takes the caller's word for what was
     * put, so recording them here excuses a real lost update.
     *
     * @param map       the ConcurrentMap being mutated (null-safe)
     * @param key       the key involved (may be {@code null})
     * @param value     what the caller put, or would have put had the key been absent, compared
     *                  by identity; {@code null} says nothing and keeps the site reportable
     * @param operation descriptive label for reports (may be {@code null})
     * @param thread    the thread performing the sequence
     * @since 1.12.3
     */
    public void recordCheckThenAct(ConcurrentMap<?, ?> map, Object key, @Nullable Object value,
                                   String operation, Thread thread) {
        if (map == null || thread == null) return;
        Site site = new Site(new IdentityKey(map), key);
        State s = sites.get(site);
        if (s == null) {
            final String label = map.getClass().getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(map));
            final String op = (operation != null) ? operation : "check-then-act";
            s = sites.computeIfAbsent(site, k -> new State(label, String.valueOf(key), op));
        }
        RoundState round = s.roundFor(SelfGuard.RoundThreads.roundNow());
        // Probed on the calling thread while it is still inside the compound operation; the
        // explicit thread parameter is attribution only.
        round.noteAccess(map, thread);
        round.notePut(value);
        // Both halves only move towards a finding, so whichever caller completes the pair sees it.
        s.judge(round);
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : sites.values()) {
            RoundState round = s.finding.get();
            if (round == null) continue;
            String msg = String.format(
                    "Non-atomic '%s' on %s for key '%s' performed by %d threads (%s) — "
                            + "check-then-act on a ConcurrentMap is not atomic; concurrent callers "
                            + "lose updates. Use putIfAbsent/computeIfAbsent/compute/merge"
                            + SelfGuard.REPORT_NOTE + ".",
                    s.operation,
                    s.mapLabel,
                    s.key,
                    round.threadCount(),
                    String.join(", ", round.threadNames()));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "NonAtomicConcurrentMapUpdate",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "map", s.mapLabel,
                            "key", s.key,
                            "operation", s.operation,
                            "threadCount", round.threadCount()),
                    Instant.now()));
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link se.deversity.asynctest.report.Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "NON-ATOMIC CONCURRENT MAP UPDATE — clean";
            StringBuilder sb = new StringBuilder("NON-ATOMIC CONCURRENT MAP UPDATE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Replace containsKey/get-then-put with putIfAbsent or computeIfAbsent.\n")
              .append("    - Replace get-then-put read-modify-write with compute or merge.\n")
              .append("    - These methods perform the whole compound operation atomically.\n");
            return sb.toString();
        }
    }
}
