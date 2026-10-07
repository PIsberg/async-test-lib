package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import java.util.Map;
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
 *       referents. The clean-up runs lazily on every {@code get}/{@code put};
 *       readers take turns at it on the reference queue's monitor, but nothing
 *       orders it against a {@code put}. Concurrent writes can produce infinite
 *       loops in the entry chain (the same family of bugs that
 *       {@code HashMap.put} concurrency caused on Java 7).</li>
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
 * <p>Synchronization awareness is partial, the same way as the rest of the shared-instance
 * family. An access recorded while the accessing thread holds a lock on every recorded access -
 * the {@code synchronized (map)} idiom, or a lock declared through
 * {@code AsyncTestContext.holdingLock(...)} - counts as guarded, and a map whose every access
 * was guarded produces no finding. A lock that was never declared is invisible and the finding
 * stands; the report wording says so.
 *
 * @since 1.6.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap-backed instance tracking; per-instance State holds ConcurrentHashMap.newKeySet() for thread ids/names.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/WeakHashMapSharedDetectorTest.java"
)
public final class WeakHashMapSharedDetector extends AbstractInstanceDetector<WeakHashMapSharedDetector.State> {

    static final class State extends SelfGuard.ThreadTrackedInstance {
        final String label;
        final String type;

        State(String label, String type) {
            this.label = label;
            this.type = type;
        }
    }

    @Override
    State newState(Object instance, String label) {
        // record() registers only the types typeOf names.
        return new State(label, java.util.Objects.requireNonNull(typeOf(instance)));
    }

    private static @Nullable String typeOf(Object map) {
        if (map instanceof WeakHashMap)    return "WeakHashMap";
        if (map instanceof IdentityHashMap) return "IdentityHashMap";
        return null;
    }

    /**
     * Record an access to a {@link WeakHashMap} or {@link IdentityHashMap}, counted as a write.
     * Other map types are ignored (this detector is type-specific). Record a read with
     * {@link #recordRead}, which only a write races.
     *
     * @param map    the map being accessed (null-safe; other map types ignored)
     * @param name   descriptive label (may be {@code null})
     * @param thread accessing thread
     */
    public void recordAccess(Map<?, ?> map, String name, Thread thread) {
        record(map, name, true, thread);
    }

    /**
     * Record a read of a {@link WeakHashMap} or {@link IdentityHashMap}: a {@code get},
     * {@code containsKey}, {@code size} or iteration. Other map types are ignored.
     *
     * <p>{@link #recordAccess} counts every access as a write, so gets under one read lock beside
     * puts under the write lock were reported. A read here only races a write: reads alone in a
     * round, and reads under a shared read lock whose writes hold the write lock, are no finding.
     * A {@code WeakHashMap} read does expunge cleared entries, but the JDK unlinks each one inside
     * {@code synchronized (queue)}, so readers take turns, and keeps the unlinked entry's
     * {@code next} for a traversal standing on it (#807, #820).
     *
     * @param map    the map being read (null-safe; other map types ignored)
     * @param name   descriptive label (may be {@code null})
     * @param thread reading thread
     * @since 1.12.3
     */
    public void recordRead(Map<?, ?> map, String name, Thread thread) {
        record(map, name, false, thread);
    }

    private void record(Map<?, ?> map, String name, boolean forWrite, Thread thread) {
        if (map == null || thread == null) return;
        String type = typeOf(map);
        if (type == null) return; // not our concern
        State s = stateFor(map, name, type);
        // Probed on the accessing thread, which is the one inside (or outside) the guarded
        // region; the explicit thread parameter is attribution only.
        s.noteAccess(map, forWrite, thread);
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : states()) {
            if (!s.sharedAndUnguarded()) continue;
            String specificRisk = "WeakHashMap".equals(s.type)
                    ? "GC-driven entry removal mutates the internal table on every "
                            + "get()/put() without locking — concurrent access can produce "
                            + "infinite loops in the entry chain"
                    : "open-addressing with linear probing can silently drop or duplicate "
                            + "entries when concurrent puts shift past the probe range";
            String msg = String.format(
                    "'%s' (type=%s) accessed from %d threads (%s) — %s is not thread-safe; %s"
                            + SelfGuard.REPORT_NOTE + ".",
                    s.label,
                    s.type,
                    s.threadCount(),
                    String.join(", ", s.threadNames()),
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
                            "threadCount", s.threadCount()),
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
