package se.deversity.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * <p>Two of those are grounded in something the caller's code did rather than in how the run was
 * scheduled. The read/write finding needs a non-thread-safe cache, a read, a write, more than one
 * thread, and no lock held across every access; one thread using its own {@code HashMap}, or two
 * threads that both go through {@code synchronized (cache)}, is correct code and stays silent.
 * The stampede finding needs the same key written by more than one thread, which is duplicated
 * computation of one value - not a count of how many threads happened to be inside the record
 * methods at once, which under {@code @AsyncTest} measures the runner's barrier.
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

    /**
     * How many distinct keys one cache is tracked at. A stampede shows up on the first hot key,
     * so the cap costs nothing to the finding while keeping the per-run allocation bounded - the
     * runner holds an 80,000-byte-per-execution budget that a test with an unbounded key space
     * would otherwise blow.
     */
    private static final int MAX_TRACKED_KEYS = 512;

    private static class CacheState extends SelfGuard.TrackedInstance {
        final String name;
        final Map<Object, Object> cache;
        final AtomicInteger readCount = new AtomicInteger(0);
        final AtomicInteger writeCount = new AtomicInteger(0);
        final Set<Long> readerThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> writerThreads = ConcurrentHashMap.newKeySet();
        /**
         * Threads that wrote each key. A cache stampede is several threads recomputing the same
         * value at once, so the same key written by more than one thread is the shape - and,
         * unlike a count of threads simultaneously inside the record methods, it is a property of
         * the caller's cache rather than of the runner's barrier (#497).
         */
        final Map<Object, Set<Long>> writerThreadsByKey = new ConcurrentHashMap<>();
        volatile boolean iterationDetected = false;

        CacheState(Map<Object, Object> cache, String name) {
            this.cache = cache;
            this.name = name;
        }

        void noteWrite(Object key) {
            if (key == null || writerThreadsByKey.size() >= MAX_TRACKED_KEYS
                    && !writerThreadsByKey.containsKey(key)) {
                return;
            }
            writerThreadsByKey
                .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(Thread.currentThread().threadId());
        }

        /** {@return how many distinct threads touched this cache at all} */
        int distinctThreads() {
            Set<Long> all = new java.util.HashSet<>(readerThreads);
            all.addAll(writerThreads);
            return all.size();
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
            final String label = name != null ? name : "cache-" + cacheKey;
            // computeIfAbsent, not get-then-put: two threads racing on a cache's first access
            // both saw null, both built a CacheState and the second put discarded the first, so
            // readerThreads/writerThreads each held one id and the cross-thread contention this
            // detector exists to measure was invisible exactly when it was real.
            state = caches.computeIfAbsent(cacheKey, k -> new CacheState(typedCache, label));
        }
        
        // Probe the locks first, while the caller is still inside whatever region it is in.
        state.noteAccess(cache, false);
        state.readCount.incrementAndGet();
        state.readerThreads.add(Thread.currentThread().threadId());
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
            final String label = name != null ? name : "cache-" + cacheKey;
            // Atomic auto-register - see recordGet() for what get-then-put cost here.
            state = caches.computeIfAbsent(cacheKey, k -> new CacheState(typedCache, label));
        }
        
        state.noteAccess(cache, true);
        state.writeCount.incrementAndGet();
        state.writerThreads.add(Thread.currentThread().threadId());
        state.noteWrite(key);
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

            // Check for concurrent read/write on non-concurrent map.
            // Three things have to hold, and only the first used to. Reads and writes on one
            // thread are not concurrent anything, and reads and writes that every thread made
            // under one common lock are guarded, so neither is a finding (#497).
            boolean isConcurrentMap = synchronizesItself(state.cache);
            if (!isConcurrentMap && reads > 0 && writes > 0
                    && state.distinctThreads() > 1 && state.sawUnguardedAccess()) {
                report.concurrentReadWrite.add(String.format(
                    "%s: concurrent reads (%d) and writes (%d) from %d threads on a "
                        + "non-thread-safe cache%s",
                    state.name, reads, writes, state.distinctThreads(), SelfGuard.REPORT_NOTE));
            }

            // Check for iteration with concurrent writes
            if (state.iterationDetected && writes > 0 && !isConcurrentMap) {
                report.iterationDuringModification.add(String.format(
                    "%s: iteration detected with %d writes on non-concurrent map",
                    state.name, writes));
            }

            // Check for cache stampede: the same key recomputed by more than one thread.
            // This used to count how many threads were inside recordGet/recordPut at once, which
            // under @AsyncTest measures the runner's barrier - it engineers exactly that overlap -
            // rather than anything about the cache. A key written by several threads is duplicated
            // work on one value, which is what a stampede is (#497).
            // The receiver's type gates this the way it gates the rules above. The corpus pins
            // that directly: recorded_lruMap_getAndPut and recorded_caffeineAsMap_getAndPut hand
            // the detector identical evidence and differ only in the receiver, so a finding on the
            // ConcurrentMap view is noise on correct code whichever rule produces it.
            for (Map.Entry<Object, Set<Long>> entry :
                    (isConcurrentMap ? Map.<Object, Set<Long>>of() : state.writerThreadsByKey)
                        .entrySet()) {
                int recomputingThreads = entry.getValue().size();
                if (recomputingThreads > 1) {
                    report.cacheStampede.add(String.format(
                        "%s: key '%s' was recomputed by %d threads (cache stampede: each miss "
                            + "started its own computation of the same value)",
                        state.name, entry.getKey(), recomputingThreads));
                }
            }

            // Track thread activity
            if (!state.readerThreads.isEmpty() || !state.writerThreads.isEmpty()) {
                report.threadActivity.put(state.name, String.format(
                    "%d reader threads, %d writer threads",
                    state.readerThreads.size(),
                    state.writerThreads.size()));
            }
        }

        return report;
    }

    /**
     * {@return whether {@code cache}'s own type answers for thread safety}
     *
     * <p>Asked of the interface, not of the concrete class. This used to be
     * {@code instanceof ConcurrentHashMap}, which is the one implementation rather than the
     * contract: Caffeine's {@code asMap()} view, Guava's cache, a {@code ConcurrentSkipListMap}
     * and a user's own {@code ConcurrentMap} all keep the same promise and were all reported as
     * a "non-thread-safe cache". The corpus eval's recording lane caught it on Caffeine, whose
     * javadoc says in as many words that the view is a thread-safe map.
     *
     * <p>{@code ConcurrentMap} is a contract its implementor has to keep, wherever it lives, so
     * this is both narrower than a package prefix and more general than a class check. The
     * legacy synchronized collections carry the same promise by a different route - every method
     * takes the instance's own monitor - and are listed for the same reason
     * {@code AgentCollectionHooks} lists them on the woven path. The two paths now give the same
     * answer to the same question.
     *
     * @param cache the registered cache
     */
    private static boolean synchronizesItself(Map<?, ?> cache) {
        return cache instanceof ConcurrentMap
                || cache instanceof java.util.Hashtable
                || cache.getClass().getName().startsWith("java.util.Collections$Synchronized");
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
         *
         * @return {@code true} when this detector recorded something worth reporting
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

            sb.append("""
  Why: Non-thread-safe caches allow concurrent reads and writes to corrupt internal state, producing stale hits,
       partially-written values, or infinite loops inside HashMap.get() under concurrent rehashing.
  Fix: replace with ConcurrentHashMap (lock-free reads, fine-grained write locks); or wrap with Collections.synchronizedMap()
       and hold the map lock for the full check-then-act sequence: synchronized(map) { if (!map.containsKey(k)) map.put(k, compute()); }\
""");
            return sb.toString();
        }
    }
}
