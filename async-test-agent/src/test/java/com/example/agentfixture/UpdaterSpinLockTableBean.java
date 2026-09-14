package com.example.agentfixture;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * A volatile table replaced only under a compare-and-swap spinlock taken through an
 * {@link AtomicIntegerFieldUpdater}, the shape older netty and JDK-style code uses (#558).
 *
 * <p>The release shapes the weaver observes are the swap back, a {@code set} through the updater
 * and a plain write of the flag. The last method releases with {@code getAndSet}, which the weaver
 * does not substitute, and then writes a second table outside the lock: that write must not read as
 * guarded by a lock the thread no longer holds.
 */
public final class UpdaterSpinLockTableBean {

    private static final AtomicIntegerFieldUpdater<UpdaterSpinLockTableBean> BUSY =
            AtomicIntegerFieldUpdater.newUpdater(UpdaterSpinLockTableBean.class, "busy");

    private volatile Object[] table;
    private volatile int busy;

    private volatile Object[] afterRelease;

    /** Replaces the table under the spinlock, released by writing 0 back. */
    public int growReleasedByWrite() {
        if (busy == 0 && BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                busy = 0;
            }
        }
        return length(table);
    }

    /** Replaces the table under the spinlock, released by a second compare-and-swap. */
    public int growReleasedByCompareAndSet() {
        if (BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                BUSY.compareAndSet(this, 1, 0);
            }
        }
        return length(table);
    }

    /** Replaces the table under the spinlock, released by {@code set} through the updater. */
    public int growReleasedBySet() {
        if (BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                BUSY.set(this, 0);
            }
        }
        return length(table);
    }

    /**
     * The twin: the acquire is observed, the release ({@code getAndSet}) is not, and the thread then
     * replaces {@link #afterRelease} with nothing held. Two threads do that with nothing excluding
     * them, which is a race whatever the lockset believed.
     */
    public int growThenWriteAfterUnobservedRelease() {
        if (BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                BUSY.getAndSet(this, 0);
            }
            afterRelease = next(afterRelease);
        }
        return length(afterRelease);
    }

    private static int length(Object[] seen) {
        return seen == null ? 0 : seen.length;
    }

    private static Object[] next(Object[] current) {
        return current == null || current.length >= 64 ? new Object[1]
                : Arrays.copyOf(current, current.length * 2);
    }
}
