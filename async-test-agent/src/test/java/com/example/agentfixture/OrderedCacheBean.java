package com.example.agentfixture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A cache read under the read view and written under the write view of one lock, over a
 * {@link LinkedHashMap} built with the three-argument constructor (#807).
 *
 * <p>That idiom is correct for an insertion-ordered map, whose {@code get} only reads, and wrong for
 * an access-ordered one, the usual LRU cache, whose {@code get} relinks the entry it returns: the
 * read view admits every other reader doing the same. The order is private to {@code java.util}, so
 * only the constructor call says which one a map is. Each shape constructs its map here, in woven
 * code, which is what lets the agent see that call; the report is labelled by the map's class, so
 * the two subclasses and the plain map tell the subjects apart.
 */
public final class OrderedCacheBean {

    /** A bounded map in access order, built through its superclass call, as an LRU cache is. */
    public static final class LruMap extends LinkedHashMap<String, String> {

        private static final long serialVersionUID = 1L;

        /** Builds an empty map in access order. */
        public LruMap() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 64;
        }
    }

    /** The same bounded map in insertion order: a {@code get} here only reads. */
    public static final class InsertionMap extends LinkedHashMap<String, String> {

        private static final long serialVersionUID = 1L;

        /** Builds an empty map in insertion order. */
        public InsertionMap() {
            super(16, 0.75f, false);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 64;
        }
    }

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final Map<String, String> entries;

    private OrderedCacheBean(Map<String, String> entries) {
        this.entries = entries;
        entries.put("key", "value");
    }

    /**
     * {@return a cache over a plain {@code LinkedHashMap} in the order given}
     *
     * @param accessOrder whether the map is in access order, passed to the constructor as a value
     *                    rather than a constant, which is the shape the agent has to capture
     */
    public static OrderedCacheBean plain(boolean accessOrder) {
        return new OrderedCacheBean(new LinkedHashMap<>(16, 0.75f, accessOrder));
    }

    /** {@return a cache over an {@link LruMap}} */
    public static OrderedCacheBean lru() {
        return new OrderedCacheBean(new LruMap());
    }

    /** {@return a cache over an {@link InsertionMap}} */
    public static OrderedCacheBean insertionOrdered() {
        return new OrderedCacheBean(new InsertionMap());
    }

    /**
     * {@return the cached value, read under the read view}
     *
     * @param key the key
     */
    public String lookup(String key) {
        rw.readLock().lock();
        try {
            return entries.get(key);
        } finally {
            rw.readLock().unlock();
        }
    }

    /** Writes under the write view. @param key the key @param value the value */
    public void store(String key, String value) {
        rw.writeLock().lock();
        try {
            entries.put(key, value);
        } finally {
            rw.writeLock().unlock();
        }
    }
}
