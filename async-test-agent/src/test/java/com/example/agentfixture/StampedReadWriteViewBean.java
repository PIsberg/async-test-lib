package com.example.agentfixture;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.StampedLock;

/** Writes with a write stamp and reads through {@code asReadWriteLock().readLock()} (#1498). */
public class StampedReadWriteViewBean {

    private final StampedLock lock = new StampedLock();
    private int count;

    public void increment() {
        long stamp = lock.writeLock();
        try {
            count++;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /** {@return the read fingerprint of the locks held while writing, as recorded} */
    public long locksWhileWriting() {
        long stamp = lock.writeLock();
        try {
            return se.deversity.asynctest.diagnostics.HeldLocks.lockFingerprint(false);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /** {@return the read fingerprint of the locks held while reading through the view} */
    public long locksWhileReading() {
        Lock read = lock.asReadWriteLock().readLock();
        read.lock();
        try {
            return se.deversity.asynctest.diagnostics.HeldLocks.lockFingerprint(false);
        } finally {
            read.unlock();
        }
    }

    public int current() {
        Lock read = lock.asReadWriteLock().readLock();
        read.lock();
        try {
            return count;
        } finally {
            read.unlock();
        }
    }
}
