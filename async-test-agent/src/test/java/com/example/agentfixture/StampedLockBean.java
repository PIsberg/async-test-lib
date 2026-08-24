package com.example.agentfixture;

import java.util.concurrent.locks.StampedLock;

/**
 * Correct stamped locking: mutation under a write stamp, reads under a read stamp.
 *
 * <p>{@code StampedLock} implements no locking interface and hands back a {@code long}, so
 * neither the {@code Lock} entries nor the view entries can see it; its own call-site hooks are
 * the only way code guarded by one stops reading as unguarded.
 */
public class StampedLockBean {

    private final StampedLock stamped = new StampedLock();

    private int count;

    /** Mutates under a write stamp, which excludes every other holder. */
    public void increment() {
        long stamp = stamped.writeLock();
        try {
            count = count + 1;
        } finally {
            stamped.unlockWrite(stamp);
        }
    }

    /** Reads under a read stamp, which admits other readers and no writer. */
    public int current() {
        long stamp = stamped.readLock();
        try {
            return count;
        } finally {
            stamped.unlockRead(stamp);
        }
    }
}
