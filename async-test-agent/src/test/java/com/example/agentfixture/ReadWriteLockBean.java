package com.example.agentfixture;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Correct read-write locking: writes under the write view, reads under the read view.
 *
 * <p>The two views are two objects, so a lockset that records the view itself can never see a
 * reader and a writer agreeing on a lock. The {@code readLock()}/{@code writeLock()} call sites
 * here are what the view substitution resolves back to this one lock, in shared mode for reads.
 */
public class ReadWriteLockBean {

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();

    private int count;

    /** Writes under the write view, which excludes every other holder. */
    public void increment() {
        rw.writeLock().lock();
        try {
            count = count + 1;
        } finally {
            rw.writeLock().unlock();
        }
    }

    /** Reads under the read view, which admits other readers and no writer. */
    public int current() {
        rw.readLock().lock();
        try {
            return count;
        } finally {
            rw.readLock().unlock();
        }
    }
}
