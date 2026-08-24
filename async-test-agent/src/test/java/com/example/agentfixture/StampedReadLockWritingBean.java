package com.example.agentfixture;

import java.util.concurrent.locks.StampedLock;

/**
 * Wrong on purpose: mutation under a read stamp, which admits every other reader doing the same.
 *
 * <p>The twin of {@link StampedLockBean}, for the same reason {@code ReadLockWritingBean} exists:
 * if modelling the stamps ever stopped keeping the modes apart, this bean would read as guarded
 * and a real bug class would go silent.
 */
public class StampedReadLockWritingBean {

    private final StampedLock stamped = new StampedLock();

    private int count;

    /** Mutates under a read stamp: concurrent callers corrupt {@code count}. */
    public void increment() {
        long stamp = stamped.readLock();
        try {
            count = count + 1;
        } finally {
            stamped.unlockRead(stamp);
        }
    }
}
