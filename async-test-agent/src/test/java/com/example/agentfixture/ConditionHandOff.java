package com.example.agentfixture;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A hand-off on a {@code Condition}: a waiter awaits until a signaller sets the flag. With the
 * agent, {@code await} is a woven call, and the field reads inside the lock are scheduling points.
 */
public final class ConditionHandOff {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private boolean ready;
    private int waits;

    /**
     * Waits for the flag, or sets it and signals.
     *
     * @param waiter whether the caller is the one that waits
     * @throws InterruptedException if interrupted while waiting
     */
    public void handOff(boolean waiter) throws InterruptedException {
        lock.lock();
        try {
            if (waiter) {
                while (!ready) {
                    waits = waits + 1;
                    changed.await();
                }
            } else {
                ready = true;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** {@return how many times the waiter waited, read under the lock} */
    public int waits() {
        lock.lock();
        try {
            return waits;
        } finally {
            lock.unlock();
        }
    }
}
