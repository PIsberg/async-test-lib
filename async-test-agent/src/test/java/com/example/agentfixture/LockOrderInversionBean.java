package com.example.agentfixture;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Takes two locks in both orders, which is the shape that deadlocks in production.
 *
 * <p>Nothing here declares a lock to the library, and nothing calls a {@code record} API. The two
 * {@link ReentrantLock}s are ordinary private fields used the way any service uses them, which is
 * the point: the agent substitutes the {@code lock()} and {@code unlock()} call sites, so the
 * acquisition order is visible without the class knowing a test exists.
 *
 * <p>The inner acquisition is a {@code tryLock} rather than a {@code lock} so this fixture cannot
 * actually deadlock the build. The hold-and-wait edge the validator needs is recorded either way -
 * a successful {@code tryLock} is an acquisition made while the outer lock is still held - and a
 * fixture that hangs the suite to prove a hang is detectable would be a poor trade.
 */
public class LockOrderInversionBean {

    private final ReentrantLock alpha = new ReentrantLock();
    private final ReentrantLock beta = new ReentrantLock();

    private int work;

    /** Takes {@code alpha}, then {@code beta}. */
    public void forward() {
        alpha.lock();
        try {
            if (beta.tryLock()) {
                try {
                    work++;
                } finally {
                    beta.unlock();
                }
            }
        } finally {
            alpha.unlock();
        }
    }

    /** Takes {@code beta}, then {@code alpha} - the inversion. */
    public void reverse() {
        beta.lock();
        try {
            if (alpha.tryLock()) {
                try {
                    work++;
                } finally {
                    alpha.unlock();
                }
            }
        } finally {
            beta.unlock();
        }
    }

    /** {@return how much work ran, so the JIT cannot delete the critical sections} */
    public int work() {
        return work;
    }
}
