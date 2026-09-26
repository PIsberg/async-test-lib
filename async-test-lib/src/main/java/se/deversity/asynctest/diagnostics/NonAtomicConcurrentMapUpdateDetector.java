package se.deversity.asynctest.diagnostics;

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
 * a site. Values are compared by identity, not {@code equals}: two new empty lists are equal,
 * yet the one the map dropped loses whatever its caller adds to it next. A site any caller
 * recorded without a value is never excused.
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

    private static final class State extends SelfGuard.ThreadTrackedInstance {
        /** Stands for a site whose callers put different instances, or one that said nothing. */
        private static final Object MIXED = new Object();

        final String mapLabel;
        final String key;
        final String operation;

        /**
         * The one instance every caller so far put, {@link #MIXED} once two differed or one
         * recorded no value, {@code null} before the first call. It only moves forward, from
         * {@code null} to one instance to {@code MIXED}, so a site once unexcused stays reported.
         */
        private final AtomicReference<@Nullable Object> putValue = new AtomicReference<>();

        State(String mapLabel, String key, String operation) {
            this.mapLabel = mapLabel;
            this.key = key;
            this.operation = operation;
        }

        /** Folds one caller's put into {@link #putValue}; a {@code null} value makes the site {@code MIXED}. */
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

        /** {@return whether every caller said it put one and the same instance} */
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
     * absent. When every caller on the site put the same instance, the race lost nothing and is
     * not reported.
     *
     * <p>Only for a value chosen without reading the map. After {@code v = map.get(k);
     * map.put(k, v + 1)} two callers putting one value is the lost update itself, and a check
     * that also decides other work, such as sending once, is a defect whatever is put: record
     * those with {@link #recordCheckThenAct(ConcurrentMap, Object, String, Thread)}.
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
        // Probed on the calling thread while it is still inside the compound operation; the
        // explicit thread parameter is attribution only.
        s.noteAccess(map, thread);
        s.notePut(value);
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : sites.values()) {
            if (!s.sharedAndUnguarded() || s.oneInstancePut()) continue;
            String msg = String.format(
                    "Non-atomic '%s' on %s for key '%s' performed by %d threads (%s) — "
                            + "check-then-act on a ConcurrentMap is not atomic; concurrent callers "
                            + "lose updates. Use putIfAbsent/computeIfAbsent/compute/merge"
                            + SelfGuard.REPORT_NOTE + ".",
                    s.operation,
                    s.mapLabel,
                    s.key,
                    s.threadCount(),
                    String.join(", ", s.threadNames()));
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
                            "threadCount", s.threadCount()),
                    Instant.now()));
        }
        return r;
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
