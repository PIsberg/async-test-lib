package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link WeakHashMap} or {@link IdentityHashMap} instances accessed
 * from more than one thread.
 *
 * <p><strong>Why it matters.</strong> Both maps are explicitly documented as
 * <em>not</em> thread-safe. Beyond the usual {@code ConcurrentModificationException}
 * risk shared by all {@link java.util.HashMap}-style structures, these two have
 * additional concurrency hazards:
 *
 * <ul>
 *   <li><strong>WeakHashMap</strong> — entry removal is driven by the GC reclaiming
 *       referents. The clean-up runs lazily on every {@code get}/{@code put}
 *       and mutates the internal table without locking. Concurrent access can
 *       produce infinite loops in the entry chain (the same family of bugs
 *       that {@code HashMap.put} concurrency caused on Java 7).</li>
 *   <li><strong>IdentityHashMap</strong> — uses open addressing with linear
 *       probing on a power-of-two table. Concurrent {@code put} can shift
 *       entries past the probe range another thread is currently reading,
 *       silently dropping or duplicating entries.</li>
 * </ul>
 *
 * <p>{@link SharedCollectionDetector} covers {@code ArrayList}/{@code HashMap}/
 * {@code HashSet}; this detector covers the two specialised maps that pattern
 * leaves out and which are frequently used as caches.
 *
 * @since 1.0.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap-backed instance tracking; per-instance State holds ConcurrentHashMap.newKeySet() for thread ids/names.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/WeakHashMapSharedDetectorTest.java"
)
public final class WeakHashMapSharedDetector {

    private static final class State {
        final String label;
        final String type;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        State(String label, String type) {
            this.label = label;
            this.type = type;
        }
    }

    private final java.util.Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access to a {@link WeakHashMap} or {@link IdentityHashMap}.
     * Other map types are ignored (this detector is type-specific).
     *
     * @param map    the map being accessed (null-safe; other map types ignored)
     * @param name   descriptive label (may be {@code null})
     * @param thread accessing thread
     */
    public void recordAccess(Map<?, ?> map, String name, Thread thread) {
        if (map == null || thread == null) return;
        String type;
        if (map instanceof WeakHashMap)         type = "WeakHashMap";
        else if (map instanceof IdentityHashMap) type = "IdentityHashMap";
        else return; // not our concern

        int id = System.identityHashCode(map);
        State s = instances.get(id);
        if (s == null) {
            final String finalType = type;
            s = instances.computeIfAbsent(id, k -> new State(
                    (name != null) ? name : finalType + "@" + k,
                    finalType));
        }
        s.accessingThreadIds.add(thread.getId());
        s.accessingThreadNames.add(thread.getName());
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
            String specificRisk = "WeakHashMap".equals(s.type)
                    ? "GC-driven entry removal mutates the internal table on every "
                            + "get()/put() without locking — concurrent access can produce "
                            + "infinite loops in the entry chain"
                    : "open-addressing with linear probing can silently drop or duplicate "
                            + "entries when concurrent puts shift past the probe range";
            String msg = String.format(
                    "'%s' (type=%s) accessed from %d threads (%s) — %s is not thread-safe; %s.",
                    s.label,
                    s.type,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames),
                    s.type,
                    specificRisk);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "WeakHashMapShared",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "type", s.type,
                            "threadCount", s.accessingThreadIds.size()),
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
            if (violations.isEmpty()) return "WEAK / IDENTITY HASH MAP — clean";
            StringBuilder sb = new StringBuilder("SHARED WEAK / IDENTITY HASH MAP DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - For WeakHashMap-style semantics: use Collections.synchronizedMap(new WeakHashMap<>())\n")
              .append("      or, for write-heavy caches, prefer Caffeine / Guava Cache with weakKeys().\n")
              .append("    - For IdentityHashMap-style identity-equality semantics: synchronize externally,\n")
              .append("      or use a ConcurrentHashMap keyed on System.identityHashCode plus a tiebreaker.\n");
            return sb.toString();
        }
    }
}
