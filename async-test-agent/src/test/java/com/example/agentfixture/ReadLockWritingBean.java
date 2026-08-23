package com.example.agentfixture;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Wrong on purpose: mutation under the read view, which admits every other reader doing the same.
 *
 * <p>The twin of {@link ReadWriteLockBean}. If resolving both views to their owner ever stopped
 * keeping the modes apart, this bean would read as guarded, and a real bug class would go silent:
 * the whole point of the shared flag is that this fixture keeps firing while its twin stays quiet.
 */
public class ReadLockWritingBean {

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();

    private int count;

    /** Mutates under the read view: concurrent callers corrupt {@code count}. */
    public void increment() {
        rw.readLock().lock();
        try {
            count = count + 1;
        } finally {
            rw.readLock().unlock();
        }
    }
}
