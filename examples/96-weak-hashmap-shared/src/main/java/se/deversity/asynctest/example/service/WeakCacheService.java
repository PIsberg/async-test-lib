package se.deversity.asynctest.example.service;

import java.util.WeakHashMap;

/**
 * Simple cache backed by a {@link WeakHashMap}.
 *
 * <p><strong>Bug:</strong> The cache is shared across threads with no synchronization.
 * {@link WeakHashMap} is not thread-safe: concurrent {@code put()} calls can corrupt
 * the internal hash table, and the GC-driven entry removal runs at arbitrary times
 * alongside active mutations, causing {@link java.util.ConcurrentModificationException}
 * or silent data loss.
 *
 * <p><strong>Fix:</strong> Use {@code Collections.synchronizedMap(new WeakHashMap<>())}
 * with external locking during iteration, or replace with a proper concurrent cache.
 */
public class WeakCacheService {

    // BUG: WeakHashMap is not thread-safe — do not share without synchronization
    private final WeakHashMap<Object, String> cache = new WeakHashMap<>();

    /**
     * Stores a value in the cache. Not thread-safe.
     */
    public void put(Object key, String value) {
        cache.put(key, value);
    }

    /**
     * Retrieves a value from the cache. Not thread-safe.
     */
    public String get(Object key) {
        return cache.get(key);
    }

    /**
     * Returns the current cache size. Not thread-safe.
     */
    public int size() {
        return cache.size();
    }

    /** Returns the raw cache map for test instrumentation. */
    public WeakHashMap<Object, String> getCache() {
        return cache;
    }
}
