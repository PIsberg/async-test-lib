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
 * <p>Both acquisitions block. This fixture used a {@code tryLock} for the inner one, which is the
 * back-off idiom that cannot deadlock, and the validator reporting it was the false positive of
 * #1242: an acquisition that cannot wait is never the waiting side of a cycle. Its caller keeps
 * the build from really deadlocking by never running {@link #forward()} and {@link #reverse()}
 * at once, through a monitor in a class the agent does not weave, so the validator still sees two
 * threads take the pair in opposite orders under no common lock.
 */
public class LockOrderInversionBean {

    private final ReentrantLock alpha = new ReentrantLock();
    private final ReentrantLock beta = new ReentrantLock();

    private int work;

    /** Takes {@code alpha}, then {@code beta}. */
    public void forward() {
        alpha.lock();
        try {
            beta.lock();
            try {
                work++;
            } finally {
                beta.unlock();
            }
        } finally {
            alpha.unlock();
        }
    }

    /** Takes {@code beta}, then {@code alpha} - the inversion. */
    public void reverse() {
        beta.lock();
        try {
            alpha.lock();
            try {
                work++;
            } finally {
                alpha.unlock();
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
