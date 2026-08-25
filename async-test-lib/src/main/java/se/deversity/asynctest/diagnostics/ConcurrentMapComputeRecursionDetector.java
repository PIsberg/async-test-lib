package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects recursive calls to {@link ConcurrentHashMap#computeIfAbsent} (or
 * {@code compute} / {@code computeIfPresent} / {@code merge}) on the same map and key
 * from the same thread — a well-known JDK footgun.
 *
 * <p>The most common trigger is recursive memoization:
 * <pre>{@code
 * cache.computeIfAbsent(key, k -> cache.computeIfAbsent(k, expensiveLoader));
 * }</pre>
 *
 * <h2>What actually happens, and what this can therefore see</h2>
 *
 * <p>The outcome is not one thing. Measured on JDK 26 against the implementations the corpus
 * resolves, a same-key re-entry splits three ways:
 *
 * <ul>
 *   <li><strong>The key is absent, on {@code ConcurrentHashMap.computeIfAbsent} or
 *       {@code compute}</strong> (and on Caffeine's {@code asMap()}, which delegates to one):
 *       the bin holds a {@code ReservationNode} and the re-entry throws
 *       {@link IllegalStateException} with the message {@code "Recursive update"}. The nested
 *       mapping function never runs.</li>
 *   <li><strong>The key is present, on {@code merge}</strong> (and on {@code compute} over an
 *       existing entry): the bin holds a real node, the re-entry re-acquires that node's monitor,
 *       and a monitor is reentrant. The nested call completes normally, and the outer call's
 *       return value then overwrites whatever the nested one stored. The update is lost with
 *       nothing thrown and nothing logged. {@code ConcurrentSkipListMap} and Spring's
 *       {@code ConcurrentReferenceHashMap} behave this way for every method, present key or
 *       not: they carry no re-entry check at all.</li>
 *   <li><strong>Guava's {@code Cache.asMap()}</strong>: the thread waits on its own in-progress
 *       computation and never returns.</li>
 * </ul>
 *
 * <p>This detector observes exactly the middle case, and that is a feature rather than a
 * limitation. Its evidence is a second {@code recordComputeStart} raised from inside a nested
 * mapping function, so a shape where that function never runs cannot reach it: the exception
 * case arrives as a stack trace naming its own cause, and the Guava case arrives as a hung
 * build. Both announce themselves. The silent lost update is the one that does not, and it is
 * the one left over.
 *
 * <h2>The same key, and any key</h2>
 *
 * <p>Two re-entries are reported, and kept apart in the report because their consequences
 * differ (#343). A nested compute on the <strong>same key</strong> has its result discarded by
 * the outer call. A nested compute on a <strong>different key of the same map</strong> is
 * forbidden just as plainly, because the contract is "the mapping function must not modify this
 * map" rather than "must not modify this key", and it is the shape more likely to be in code
 * that ships: measured over 200 fresh maps it ran and returned 198 times and threw twice, on the
 * runs where both keys landed in the same bin. The map is left updated in an order the caller
 * did not intend either way.
 *
 * <p>Nesting on a <em>different map</em> is not reported. The contract is per map, and a mapping
 * function that consults some other structure is ordinary code; reporting it would make the
 * detector fire on the common case rather than on a defect.
 *
 * <p>Java 8 is a fourth outcome, an infinite loop inside the resize path, and is out of scope:
 * this library targets 21 and later.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.concurrentMapComputeRecursionMonitor();
 * mon.recordComputeStart(cache, key, Thread.currentThread(), "cache");
 * try {
 *     // mapping function body
 * } finally {
 *     mon.recordComputeEnd(cache, key, Thread.currentThread());
 * }
 * }</pre>
 */
public class ConcurrentMapComputeRecursionDetector {

    /**
     * The keys currently being computed, per {@code mapIdentityHash:threadId} scope.
     *
     * <p>Keyed by scope rather than by the whole {@code map:key:thread} slot so that the question
     * "is this thread already inside a compute on this map" is answerable, which is what the
     * cross-key rule needs (#343). The inner map holds each key's identity hash against a label
     * captured at entry; neither the map nor the key is retained, so an entry cannot keep a
     * subject alive. Every scope names one thread, so only that thread ever touches its inner
     * map, and an empty one is dropped rather than left to accumulate across a long run.
     */
    private final Map<String, Map<Integer, String>> activeByScope = new ConcurrentHashMap<>();

    private final List<String> recursions          = new CopyOnWriteArrayList<>();
    private final List<String> crossKeyRecursions  = new CopyOnWriteArrayList<>();

    private static String scope(Object map, Thread thread) {
        return System.identityHashCode(map) + ":" + thread.threadId();
    }

    /**
     * Record entry into a {@code compute*} / {@code merge} mapping function.
     *
     * <p>Reports two different things, because they are two different defects with two different
     * consequences (#341, #343):
     *
     * <ul>
     *   <li>the same key is already being computed by this thread on this map, so the nested
     *       call's result is discarded by the outer one;</li>
     *   <li>a different key of the same map is, which the map's contract forbids just as
     *       plainly and which usually returns normally, so it survives review.</li>
     * </ul>
     *
     * <p>Nesting on a <em>different map</em> is neither, and is not reported: the contract is
     * per map, and a mapping function that consults some other structure is ordinary code.
     *
     * @param map     the ConcurrentHashMap (null-safe)
     * @param key     the key being computed (null-safe)
     * @param thread  the calling thread (null-safe)
     * @param mapName descriptive name for reports
     */
    public void recordComputeStart(Map<?, ?> map, Object key, Thread thread, String mapName) {
        if (map == null || key == null || thread == null) return;
        // computeIfAbsent rather than get-then-put: a get/null-check/put here loses an entry the
        // moment two threads open a scope at once, and a lost entry is a missed finding.
        Map<Integer, String> active =
                activeByScope.computeIfAbsent(scope(map, thread), ignored -> new ConcurrentHashMap<>());
        int keyIdentity = System.identityHashCode(key);
        String label = mapName != null ? mapName : "map@" + System.identityHashCode(map);

        if (active.containsKey(keyIdentity)) {
            recursions.add(String.format(
                "Thread '%s': recursive compute*()/merge() on %s for key '%s' - the nested "
                + "mapping function was entered, so the outer call's return value overwrites "
                + "what it stored and that update is lost with nothing thrown",
                thread.getName(), label, key));
        } else if (!active.isEmpty()) {
            crossKeyRecursions.add(String.format(
                "Thread '%s': compute*()/merge() on %s for key '%s' entered while key(s) %s on "
                + "the same map were still being computed - the mapping function must not modify "
                + "its own map, whichever key it modifies. This one usually returns normally, "
                + "which is why it survives review; it throws IllegalStateException on the runs "
                + "where the two keys land in the same bin, and either way the map is updated in "
                + "an order the caller did not intend",
                thread.getName(), label, key, active.values()));
        }

        active.put(keyIdentity, String.valueOf(key));
    }

    /**
     * Record exit from a {@code compute*} / {@code merge} mapping function.
     *
     * @param map the map the computation was running on, tracked by identity
     * @param key the key the entry is stored under
     * @param thread the thread performing the operation
     */
    public void recordComputeEnd(Map<?, ?> map, Object key, Thread thread) {
        if (map == null || key == null || thread == null) return;
        String scope = scope(map, thread);
        Map<Integer, String> active = activeByScope.get(scope);
        if (active == null) {
            return;
        }
        active.remove(System.identityHashCode(key));
        // Only this thread writes to this scope, so an empty inner map here stays empty until
        // this thread opens it again. Dropping it keeps a long run over many short-lived maps
        // from accumulating one entry per map.
        if (active.isEmpty()) {
            activeByScope.remove(scope, active);
        }
    }

    /**
     * {@return report of recursive compute calls}
     */
    public ConcurrentMapComputeRecursionReport analyze() {
        ConcurrentMapComputeRecursionReport r = new ConcurrentMapComputeRecursionReport();
        r.recursions.addAll(recursions);
        r.crossKeyRecursions.addAll(crossKeyRecursions);
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class ConcurrentMapComputeRecursionReport {
        final List<String> recursions = new ArrayList<>();

        /**
         * Re-entries on a <em>different</em> key of the same map (#343).
         *
         * <p>Kept apart from {@link #recursions} because the two have different consequences and
         * a reader has to be able to tell them apart: the same-key case discards the nested
         * result, while this one usually returns normally and leaves the map updated out of
         * order. Merging them would hide that behind one count.
         */
        final List<String> crossKeyRecursions = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !recursions.isEmpty() || !crossKeyRecursions.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("CONCURRENT MAP COMPUTE RECURSION DETECTED:\n");
            for (String r : recursions) sb.append("  - ").append(r).append("\n");
            for (String r : crossKeyRecursions) sb.append("  - ").append(r).append("\n");
            sb.append("""
  Why: A compute*()/merge() mapping function must not touch the same map. What that does depends on
       the map and on whether the key was already there, and only one of the outcomes is silent.
       That silent one is what is reported here; the others announce themselves.
         - Key absent, ConcurrentHashMap.computeIfAbsent()/compute(): the bin holds a reservation
           node, and the re-entry throws IllegalStateException("Recursive update"). The nested
           mapping function never runs, so this detector never sees it and does not need to: the
           stack trace names its own cause.
         - Key present, merge() or compute() over an existing entry: the bin holds a real node
           whose monitor the re-entry re-acquires, and a monitor is reentrant. The nested call
           completes, the outer call's return value then overwrites what it stored, and the update
           is lost with nothing thrown and nothing logged. ConcurrentSkipListMap and Spring's
           ConcurrentReferenceHashMap behave this way for every method, present key or not.
         - Guava's Cache.asMap(): the thread waits on its own in-progress computation forever.
       A re-entry on a DIFFERENT key of the same map is reported too, and is the shape most likely
       to be in code that ships: ConcurrentHashMap's contract is "the mapping function must not
       modify this map", not "must not modify this key", and measured over 200 fresh maps a nested
       computeIfAbsent on another key ran and returned 198 times and threw twice, on the runs where
       both keys landed in the same bin. It is the same defect with a better disguise, so it is
       listed separately rather than merged into the count above.
       Nesting on a different map is not reported: the contract is per map, and a mapping function
       that consults some other structure is ordinary code.
  Fix:
    - Compute the new value first, in a local variable, then store it in a single call
    - Restructure the algorithm to avoid re-entrant compute*()/merge() on the same key
    - For recursive structures, use a separate auxiliary map or a stack-based approach\
""");
            return sb.toString();
        }
    }
}
