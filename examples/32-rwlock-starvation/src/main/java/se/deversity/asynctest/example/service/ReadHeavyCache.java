package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A product-catalog cache that allows many concurrent readers and exclusive writers.
 *
 * BUG: The {@code ReentrantReadWriteLock} is constructed with {@code fair = false}
 * (the default). A non-fair lock allows reader threads to acquire the read lock
 * immediately as long as no writer holds it — including when a writer is already
 * queued and waiting.
 *
 * Under a read-heavy workload (many concurrent lookups, occasional cache
 * invalidations), new readers can continuously cut in front of a waiting
 * writer, delaying it indefinitely. Cache entries that should be expired
 * remain visible to all readers for far longer than the intended TTL.
 *
 * In production this can manifest as stale product prices, outdated inventory
 * levels, or configuration values that never refresh under load.
 *
 * FIX: Construct the lock with {@code fair = true}:
 * <pre>
 *   new ReentrantReadWriteLock(true)
 * </pre>
 * A fair lock queues write requests and prevents new readers from jumping
 * the queue, bounding the maximum wait time for writers.
 */
public class ReadHeavyCache {

    // BUG: non-fair lock — readers can continuously starve waiting writers
    private final ReadWriteLock lock = new ReentrantReadWriteLock(false);

    private final Map<String, String> store = new HashMap<>();

    /**
     * Look up a value in the cache. Many threads can hold the read lock simultaneously.
     */
    public String get(String key) {
        lock.readLock().lock();
        try {
            return store.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Insert or update a cache entry. Requires exclusive write access.
     *
     * STARVATION RISK: with a non-fair lock, this method can be delayed
     * indefinitely when there is a continuous stream of readers.
     */
    public void update(String key, String value) {
        lock.writeLock().lock();
        try {
            store.put(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Invalidate a cache entry. Requires exclusive write access.
     */
    public void invalidate(String key) {
        lock.writeLock().lock();
        try {
            store.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ReadWriteLock getLock() {
        return lock;
    }

    public int size() {
        lock.readLock().lock();
        try {
            return store.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
