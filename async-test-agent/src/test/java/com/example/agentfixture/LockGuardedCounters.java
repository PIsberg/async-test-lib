package com.example.agentfixture;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Two counters guarded correctly, one by a monitor and one by a {@code ReentrantLock}. With field
 * weaving, the read and the write inside each critical section are scheduling points, so a
 * controlled schedule pauses a worker while it holds the lock.
 */
public final class LockGuardedCounters {

    private final Object monitor = new Object();
    private final ReentrantLock lock = new ReentrantLock();
    private int underMonitor;
    private int underLock;

    /** Increments under the monitor. */
    public void incrementUnderMonitor() {
        synchronized (monitor) {
            underMonitor = underMonitor + 1;
        }
    }

    /** Increments under the lock. */
    public void incrementUnderLock() {
        lock.lock();
        try {
            underLock = underLock + 1;
        } finally {
            lock.unlock();
        }
    }

    /** {@return the monitor-guarded count, read under its monitor} */
    public int monitorCount() {
        synchronized (monitor) {
            return underMonitor;
        }
    }

    /** {@return the lock-guarded count, read under its lock} */
    public int lockCount() {
        lock.lock();
        try {
            return underLock;
        } finally {
            lock.unlock();
        }
    }
}
