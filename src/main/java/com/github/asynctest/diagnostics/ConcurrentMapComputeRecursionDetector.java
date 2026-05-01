package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Detects recursive calls to {@link ConcurrentHashMap#computeIfAbsent} (or
 * {@code compute} / {@code computeIfPresent} / {@code merge}) on the same map and key
 * from the same thread — a well-known JDK footgun.
 *
 * <p>In Java 8 this causes an infinite loop (the mapping function never returns).
 * In Java 9+ it throws {@link IllegalStateException}. The most common trigger is
 * recursive memoization:
 * <pre>{@code
 * cache.computeIfAbsent(key, k -> cache.computeIfAbsent(k, expensiveLoader));
 * }</pre>
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
                + ":" + thread.getId();
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
                "Thread '%s': recursive compute*() on %s for key '%s' — "
                + "causes infinite loop (Java 8) or IllegalStateException (Java 9+)",
                thread.getName(), label, key));
        }
    }

    /** Record exit from a {@code compute*} / {@code merge} mapping function. */
    public void recordComputeEnd(Map<?, ?> map, Object key, Thread thread) {
        if (map == null || key == null || thread == null) return;
        activeComputes.remove(slot(map, key, thread));
    }

    /** @return report of recursive compute calls */
    public ConcurrentMapComputeRecursionReport analyze() {
        ConcurrentMapComputeRecursionReport r = new ConcurrentMapComputeRecursionReport();
        r.recursions.addAll(recursions);
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class ConcurrentMapComputeRecursionReport {
        final List<String> recursions = new ArrayList<>();

        public boolean hasIssues() { return !recursions.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("CONCURRENT MAP COMPUTE RECURSION DETECTED:\n");
            for (String r : recursions) sb.append("  - ").append(r).append("\n");
            sb.append("  Fix: avoid calling compute*() on the same map inside a compute*() "
                    + "mapping function for the same key; use a separate helper map "
                    + "or restructure the recursion to avoid re-entry");
            return sb.toString();
        }
    }
}
