package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

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
 * map and key — the exact precondition for a lost update.
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

    private static final class State {
        final String mapLabel;
        final String key;
        final String operation;
        final Set<Long>   threadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();

        State(String mapLabel, String key, String operation) {
            this.mapLabel = mapLabel;
            this.key = key;
            this.operation = operation;
        }
    }

    // Keyed by (identityHashCode(map), String.valueOf(key)).
    private final Map<String, State> sites = new ConcurrentHashMap<>();

    /**
     * Record that the calling thread performed a non-atomic check-then-act
     * (e.g. {@code containsKey}/{@code get} then {@code put}) against
     * {@code map} for {@code key}.
     *
     * @param map       the ConcurrentMap being mutated (null-safe)
     * @param key       the key involved (may be {@code null})
     * @param operation descriptive label for reports (may be {@code null})
     * @param thread    the thread performing the sequence
     */
    public void recordCheckThenAct(ConcurrentMap<?, ?> map, Object key, String operation, Thread thread) {
        if (map == null || thread == null) return;
        String mapId = Integer.toHexString(System.identityHashCode(map));
        String keyStr = String.valueOf(key);
        String compositeKey = mapId + '#' + keyStr;
        State s = sites.get(compositeKey);
        if (s == null) {
            final String label = map.getClass().getSimpleName() + "@" + mapId;
            final String op = (operation != null) ? operation : "check-then-act";
            s = sites.computeIfAbsent(compositeKey, k -> new State(label, keyStr, op));
        }
        s.threadIds.add(thread.getId());
        s.threadNames.add(thread.getName());
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : sites.values()) {
            if (s.threadIds.size() <= 1) continue;
            String msg = String.format(
                    "Non-atomic '%s' on %s for key '%s' performed by %d threads (%s) — "
                            + "check-then-act on a ConcurrentMap is not atomic; concurrent callers "
                            + "lose updates. Use putIfAbsent/computeIfAbsent/compute/merge.",
                    s.operation,
                    s.mapLabel,
                    s.key,
                    s.threadIds.size(),
                    String.join(", ", s.threadNames));
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
                            "threadCount", s.threadIds.size()),
                    Instant.now()));
        }
        return r;
    }

    public static final class Report {
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

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
