package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A key-value store built on a {@link ReentrantReadWriteLock}, with the three ways of moving
 * between its two locks: the wrong downgrade, the right one, and the upgrade that cannot work.
 *
 * <p><strong>BUG ({@link #updateAndRead}):</strong> the write lock is released before the read
 * lock is acquired. In the gap another thread can write the same key, so the caller reads back a
 * value it did not write. Nothing throws, and under any sequential test the value is always the
 * one just stored.
 *
 * <p><strong>FIX ({@link #updateAndReadFixed}):</strong> acquire the read lock while still
 * holding the write lock, then release the write lock. There is no gap, and no other writer can
 * interleave.
 *
 * <p><strong>A different bug entirely ({@link #readThenUpdate}):</strong> acquiring the write
 * lock while holding the read lock. ReentrantReadWriteLock does not support that: the write lock
 * waits for every read lock to be released, including the caller's own, so it waits forever.
 * This one is here because {@code LockDowngradeDetector} reports it too, alongside the unsafe
 * downgrade above. See the test class and issue #355.
 */
public class DataStore {

    private final Map<String, String> store = new HashMap<>();

    /** Exposed so a test can hand it to the detector, which tracks locks by identity. */
    public final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();

    private volatile Runnable onReadAcquired = () -> { };

    private volatile Runnable onReadReleased = () -> { };

    private volatile Runnable onWriteAcquired = () -> { };

    private volatile Runnable onWriteReleased = () -> { };

    /**
     * Writes {@code value} for {@code key} and reads it back.
     *
     * <p>BUG: incorrect downgrade. The write lock is released before the read lock is acquired.
     *
     * @param key   the key to write
     * @param value the value to write
     * @return whatever is stored under {@code key} by the time the read lock is granted
     */
    public String updateAndRead(String key, String value) {
        dataLock.writeLock().lock();
        onWriteAcquired.run();
        try {
            store.put(key, value);
        } finally {
            dataLock.writeLock().unlock();   // released too early: the gap opens here
            onWriteReleased.run();
        }

        // Another thread can write between here and the readLock.lock() below.
        dataLock.readLock().lock();
        onReadAcquired.run();
        try {
            return store.get(key);           // may be a value written by a different thread
        } finally {
            dataLock.readLock().unlock();
            onReadReleased.run();
        }
    }

    /**
     * The same operation, downgraded correctly: the read lock is taken while the write lock is
     * still held, so there is no moment at which neither is.
     *
     * @param key   the key to write
     * @param value the value to write
     * @return the value this call wrote
     */
    public String updateAndReadFixed(String key, String value) {
        dataLock.writeLock().lock();
        onWriteAcquired.run();
        try {
            store.put(key, value);
            dataLock.readLock().lock();      // acquired while the write lock is still held
            onReadAcquired.run();
        } finally {
            dataLock.writeLock().unlock();
            onWriteReleased.run();
        }
        try {
            return store.get(key);
        } finally {
            dataLock.readLock().unlock();
            onReadReleased.run();
        }
    }

    /**
     * Reads, then tries to write without letting go of the read lock.
     *
     * <p>BUG: this is the upgrade ReentrantReadWriteLock does not support. Written with
     * {@code lock()} it would block forever; written with {@code tryLock(timeout)} it simply
     * never succeeds, which is the same fact in a form a test can survive.
     *
     * @param key   the key to write
     * @param value the value to write
     * @return true if the write lock was somehow granted, which it will not be
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean readThenUpdate(String key, String value) throws InterruptedException {
        dataLock.readLock().lock();
        onReadAcquired.run();
        try {
            store.get(key);
            onWriteAcquired.run();           // the attempt is what the detector records
            boolean granted = dataLock.writeLock().tryLock(50, TimeUnit.MILLISECONDS);
            onWriteReleased.run();           // keep the detector's hold counters balanced
            if (granted) {
                try {
                    store.put(key, value);
                } finally {
                    dataLock.writeLock().unlock();
                }
            }
            return granted;
        } finally {
            dataLock.readLock().unlock();
            onReadReleased.run();
        }
    }

    /**
     * Reads a value without any write.
     *
     * @param key the key to read
     * @return the stored value, or null
     */
    public String read(String key) {
        dataLock.readLock().lock();
        onReadAcquired.run();
        try {
            return store.get(key);
        } finally {
            dataLock.readLock().unlock();
            onReadReleased.run();
        }
    }

    /**
     * Installs the hooks LockDowngradeDetector needs. No-ops by default.
     *
     * @param readAcquired  called after the read lock is granted
     * @param readReleased  called after the read lock is released
     * @param writeAcquired called as the write lock is acquired, or attempted
     * @param writeReleased called after the write lock is released, or the attempt gave up
     */
    public void observeLock(Runnable readAcquired, Runnable readReleased,
                            Runnable writeAcquired, Runnable writeReleased) {
        this.onReadAcquired = readAcquired;
        this.onReadReleased = readReleased;
        this.onWriteAcquired = writeAcquired;
        this.onWriteReleased = writeReleased;
    }
}
