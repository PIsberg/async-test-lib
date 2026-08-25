package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // slot = mapIdentityHash:keyIdentityHash:threadId
    private final Set<String>  activeComputes = ConcurrentHashMap.newKeySet();
    private final List<String> recursions     = new CopyOnWriteArrayList<>();

    private static String slot(Object map, Object key, Thread thread) {
        return System.identityHashCode(map) + ":" + System.identityHashCode(key)
                + ":" + thread.threadId();
    }

    /**
     * Record entry into a {@code compute*} / {@code merge} mapping function.
     *
     * @param map     the ConcurrentHashMap (null-safe)
     * @param key     the key being computed (null-safe)
     * @param thread  the calling thread (null-safe)
     * @param mapName descriptive name for reports
     */
    public void recordComputeStart(Map<?, ?> map, Object key, Thread thread, String mapName) {
        if (map == null || key == null || thread == null) return;
        String slot = slot(map, key, thread);
        if (!activeComputes.add(slot)) {
            String label = mapName != null ? mapName : "map@" + System.identityHashCode(map);
            recursions.add(String.format(
                "Thread '%s': recursive compute*()/merge() on %s for key '%s' - the nested "
                + "mapping function was entered, so the outer call's return value overwrites "
                + "what it stored and that update is lost with nothing thrown",
                thread.getName(), label, key));
        }
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
        activeComputes.remove(slot(map, key, thread));
    }

    /**
     * {@return report of recursive compute calls}
     */
    public ConcurrentMapComputeRecursionReport analyze() {
        ConcurrentMapComputeRecursionReport r = new ConcurrentMapComputeRecursionReport();
        r.recursions.addAll(recursions);
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class ConcurrentMapComputeRecursionReport {
        final List<String> recursions = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !recursions.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("CONCURRENT MAP COMPUTE RECURSION DETECTED:\n");
            for (String r : recursions) sb.append("  - ").append(r).append("\n");
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
  Fix:
    - Compute the new value first, in a local variable, then store it in a single call
    - Restructure the algorithm to avoid re-entrant compute*()/merge() on the same key
    - For recursive structures, use a separate auxiliary map or a stack-based approach\
""");
            return sb.toString();
        }
    }
}
