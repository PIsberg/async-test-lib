package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A simple key-value store that uses ReentrantReadWriteLock with an incorrect
 * lock-downgrade pattern.
 *
 * BUG: {@code updateAndRead} releases the write lock before acquiring the read lock.
 * This creates a window where another thread can write to the same key, causing
 * the caller to read back a value it did not write.
 */
public class DataStore {

    private final Map<String, String> store = new HashMap<>();
    // Exposed so the test can pass it to the detector
    public final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();

    /**
     * Writes {@code value} for {@code key} and then reads it back.
     *
     * BUG: incorrect downgrade — write lock is released before read lock is acquired.
     */
    public String updateAndRead(String key, String value) {
        dataLock.writeLock().lock();
        try {
            store.put(key, value);
        } finally {
            dataLock.writeLock().unlock(); // released too early — gap opens here
        }

        // Another thread can write between here and the readLock.lock() below
        dataLock.readLock().lock();
        try {
            return store.get(key); // may return a value written by a different thread
        } finally {
            dataLock.readLock().unlock();
        }
    }

    /**
     * Reads a value without any write.
     */
    public String read(String key) {
        dataLock.readLock().lock();
        try {
            return store.get(key);
        } finally {
            dataLock.readLock().unlock();
        }
    }
}
