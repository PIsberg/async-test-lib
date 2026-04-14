package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects concurrent access to non-thread-safe cache implementations.
 *
 * Common cache concurrency issues detected:
 * - HashMap/LinkedHashMap used as cache without synchronization
 * - Cache mutations during iteration (ConcurrentModificationException risk)
 * - Read-write race conditions (stale reads, lost updates)
 * - Cache stampede (multiple threads recomputing same value simultaneously)
 *
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 10, detectCacheConcurrency = true)
 * void testCache() {
 *     Map<String, Object> cache = new HashMap<>();
 *     AsyncTestContext.cacheConcurrencyDetector()
 *         .registerCache(cache, "user-cache");
 *     
 *     // Write
 *     AsyncTestContext.cacheConcurrencyDetector()
 *         .recordPut(cache, "user-cache", "key1", "value1");
 *     
 *     // Read
 *     AsyncTestContext.cacheConcurrencyDetector()
 *         .recordGet(cache, "user-cache", "key1");
 * }
 * }</pre>
 */
public class CacheConcurrencyDetector {

    private static class CacheState {
        final String name;
        final Map<Object, Object> cache;
        final AtomicInteger readCount = new AtomicInteger(0);
        final AtomicInteger writeCount = new AtomicInteger(0);
        final Set<Long> readerThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> writerThreads = ConcurrentHashMap.newKeySet();
        final AtomicInteger concurrentAccess = new AtomicInteger(0);
        final AtomicInteger maxConcurrentAccess = new AtomicInteger(0);
        volatile boolean iterationDetected = false;
        volatile boolean concurrentReadWrite = false;

        CacheState(Map<Object, Object> cache, String name) {
            this.cache = cache;
            this.name = name;
        }
    }

    private final Map<Integer, CacheState> caches = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Disable this detector.
     */
    public void disable() {
        enabled = false;
    }

    /**
     * Enable this detector.
     */
    public void enable() {
        enabled = true;
    }

    /**
     * Register a cache for monitoring.
     *
     * @param cache the cache instance (Map implementation)
     * @param name a descriptive name for tracking
     */
    public void registerCache(Map<?, ?> cache, String name) {
        if (!enabled || cache == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> typedCache = (Map<Object, Object>) cache;
        caches.computeIfAbsent(System.identityHashCode(cache),
            k -> new CacheState(typedCache, name));
    }

    /**
     * Record a cache read operation.
     *
     * @param cache the cache instance
     * @param name should match the registered name
     * @param key the key being read
     */
    public void recordGet(Map<?, ?> cache, String name, Object key) {
        if (!enabled || cache == null) {
            return;
        }
        int cacheKey = System.identityHashCode(cache);
        CacheState state = caches.get(cacheKey);
        if (state == null) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> typedCache = (Map<Object, Object>) cache;
            state = new CacheState(typedCache, name != null ? name : "cache-" + cacheKey);
            caches.put(cacheKey, state);
        }
        
        state.readCount.incrementAndGet();
        state.readerThreads.add(Thread.currentThread().threadId());

        int current = state.concurrentAccess.incrementAndGet();
        try {
            state.maxConcurrentAccess.updateAndGet(max -> Math.max(max, current));

            // Detect concurrent read+write or mixed access across threads
            if ((state.writeCount.get() > 0 && current > 1) ||
                (!state.writerThreads.isEmpty() && !state.writerThreads.contains(Thread.currentThread().threadId()))) {
                state.concurrentReadWrite = true;
            }
        } finally {
            state.concurrentAccess.decrementAndGet();
        }
    }

    /**
     * Record a cache write operation.
     *
     * @param cache the cache instance
     * @param name should match the registered name
     * @param key the key being written
     * @param value the value being set
     */
    public void recordPut(Map<?, ?> cache, String name, Object key, Object value) {
        if (!enabled || cache == null) {
            return;
        }
        int cacheKey = System.identityHashCode(cache);
        CacheState state = caches.get(cacheKey);
        if (state == null) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> typedCache = (Map<Object, Object>) cache;
            state = new CacheState(typedCache, name != null ? name : "cache-" + cacheKey);
            caches.put(cacheKey, state);
        }
        
        state.writeCount.incrementAndGet();
        state.writerThreads.add(Thread.currentThread().threadId());
        
        int current = state.concurrentAccess.incrementAndGet();
        try {
            state.maxConcurrentAccess.updateAndGet(max -> Math.max(max, current));

            // Detect concurrent read+write or mixed access across threads
            if ((state.readCount.get() > 0 && current > 1) ||
                (!state.readerThreads.isEmpty() && !state.readerThreads.contains(Thread.currentThread().threadId()))) {
                state.concurrentReadWrite = true;
            }
        } finally {
            state.concurrentAccess.decrementAndGet();
        }
    }

    /**
     * Record iteration over cache entries (dangerous during concurrent modification).
     *
     * @param cache the cache instance
     * @param name should match the registered name
     */
    public void recordIteration(Map<?, ?> cache, String name) {
        if (!enabled || cache == null) {
            return;
        }
        CacheState state = caches.get(System.identityHashCode(cache));
        if (state != null) {
            state.iterationDetected = true;
        }
    }

    /**
     * Analyze cache usage for issues.
     *
     * @return a report of detected issues
     */
    public CacheConcurrencyReport analyze() {
        CacheConcurrencyReport report = new CacheConcurrencyReport();
        report.enabled = enabled;

        for (CacheState state : caches.values()) {
            int reads = state.readCount.get();
            int writes = state.writeCount.get();

            // Check for concurrent read/write on non-concurrent map
            boolean isConcurrentMap = state.cache instanceof ConcurrentHashMap;
            if (!isConcurrentMap && reads > 0 && writes > 0) {
                report.concurrentReadWrite.add(String.format(
                    "%s: concurrent reads (%d) and writes (%d) on non-thread-safe cache",
                    state.name, reads, writes));
            }

            // Check for iteration with concurrent writes
            if (state.iterationDetected && writes > 0 && !isConcurrentMap) {
                report.iterationDuringModification.add(String.format(
                    "%s: iteration detected with %d writes on non-concurrent map",
                    state.name, writes));
            }

            // Check for cache stampede (many concurrent accesses)
            if (state.maxConcurrentAccess.get() > 10) {
                report.cacheStampede.add(String.format(
                    "%s: high concurrent access count (%d) may cause cache stampede",
                    state.name, state.maxConcurrentAccess.get()));
            }

            // Track thread activity
            if (state.readerThreads.size() > 0 || state.writerThreads.size() > 0) {
                report.threadActivity.put(state.name, String.format(
                    "%d reader threads, %d writer threads",
                    state.readerThreads.size(),
                    state.writerThreads.size()));
            }
        }

        return report;
    }

    /**
     * Report class for cache concurrency issues.
     */
    public static class CacheConcurrencyReport {
        private boolean enabled = true;
        final java.util.List<String> concurrentReadWrite = new java.util.ArrayList<>();
        final java.util.List<String> iterationDuringModification = new java.util.ArrayList<>();
        final java.util.List<String> cacheStampede = new java.util.ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !concurrentReadWrite.isEmpty() || 
                   !iterationDuringModification.isEmpty() || 
                   !cacheStampede.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "CacheConcurrencyReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("CACHE CONCURRENCY ISSUES DETECTED:\n");

            if (!concurrentReadWrite.isEmpty()) {
                sb.append("  Concurrent Read/Write:\n");
                for (String issue : concurrentReadWrite) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!iterationDuringModification.isEmpty()) {
                sb.append("  Iteration During Modification:\n");
                for (String issue : iterationDuringModification) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!cacheStampede.isEmpty()) {
                sb.append("  Cache Stampede Risk:\n");
                for (String issue : cacheStampede) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!threadActivity.isEmpty()) {
                sb.append("  Thread Activity:\n");
                for (Map.Entry<String, String> entry : threadActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use ConcurrentHashMap or synchronize cache access");
            return sb.toString();
        }
    }
}
