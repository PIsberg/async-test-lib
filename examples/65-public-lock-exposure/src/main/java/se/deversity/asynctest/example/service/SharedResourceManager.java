package se.deversity.asynctest.example.service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * BUGGY service that demonstrates public lock exposure.
 *
 * BUG: getLock() returns the exact ReentrantLock instance that guards the
 *      resource. Any external caller that calls getLock().lock() without a
 *      corresponding unlock() in a finally block will hold the lock forever,
 *      starving all threads that call accessResource().
 *
 * FIX: Remove getLock(). Keep the lock strictly private and provide only
 *      domain-level methods (accessResource, executeExclusive, etc.) that
 *      acquire and release the lock internally under try/finally.
 */
public class SharedResourceManager {

    // BUG: this lock is handed out to external callers via getLock()
    private final ReentrantLock lock = new ReentrantLock();
    private int resourceValue = 0;

    /**
     * Access the protected resource. Uses the internal lock correctly.
     */
    public int accessResource() {
        lock.lock();
        try {
            resourceValue++;
            return resourceValue;
        } finally {
            lock.unlock();
        }
    }

    /**
     * BUG: exposes the internal lock to external callers.
     * An external caller can acquire this lock and never release it.
     */
    public ReentrantLock getLock() {
        return lock;
    }

    public int getResourceValue() {
        return resourceValue;
    }
}
