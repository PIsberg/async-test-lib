package se.deversity.asynctest.example.service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * BUGGY service that demonstrates ReentrantLock hold-count imbalance.
 *
 * BUG: increment() acquires the lock, then calls validate() which acquires it
 *      again (the lock is re-entrant, so this succeeds). The finally block in
 *      increment() calls unlock() only once, leaving the hold count at 1 after
 *      the method returns. The lock is never fully released — all other threads
 *      calling increment() block forever.
 *
 * FIX: Each lock() must be paired with an unlock() in its own finally block.
 *      Extract validate() as a private helper that does NOT acquire the lock,
 *      relying on the caller's lock instead.
 */
public class CounterService {

    public final ReentrantLock lock = new ReentrantLock();
    private int count = 0;

    /**
     * Increment the counter. Thread-unsafe due to lock imbalance.
     */
    public int increment() {
        lock.lock();           // first acquisition — hold count = 1
        try {
            validate();        // BUG: validate() calls lock.lock() again → hold count = 2
            count++;
            return count;
        } finally {
            lock.unlock();     // BUG: only one unlock — hold count drops to 1, not 0
        }
    }

    /** BUG: re-acquires the lock without a paired unlock. */
    private void validate() {
        lock.lock();           // second acquisition — hold count = 2
        // missing finally { lock.unlock(); }
        if (count < 0) {
            throw new IllegalStateException("count must not be negative");
        }
    }

    public int getCount() {
        return count;
    }
}
