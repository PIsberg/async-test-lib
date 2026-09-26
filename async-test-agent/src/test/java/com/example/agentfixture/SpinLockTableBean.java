package com.example.agentfixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * A volatile table replaced only under a compare-and-swap spinlock, the way Caffeine's
 * {@code StripedBuffer} guards its buffer table with {@code tableBusy} (#554).
 *
 * <p>Readers take no lock on purpose: the table is volatile and every replacement happens with the
 * spinlock held. The twin replaces its table with no spinlock at all, which is the race.
 */
public final class SpinLockTableBean {

    private static final VarHandle BUSY;

    static {
        try {
            BUSY = MethodHandles.lookup().findVarHandle(SpinLockTableBean.class, "busy", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile Object[] table;
    private volatile int busy;

    private volatile Object[] unguardedTable;
    private volatile Object[] afterRelease;

    /** Replaces the table under the spinlock, released by writing 0 back like Caffeine does. */
    public int growReleasedByWrite() {
        if (busy == 0 && BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                busy = 0;
            }
        }
        Object[] seen = table;
        return seen == null ? 0 : seen.length;
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
        Object[] seen = table;
        return seen == null ? 0 : seen.length;
    }

    /** The twin: the same replacement and the same read with nothing excluding the writers. */
    public int growUnguarded() {
        unguardedTable = next(unguardedTable);
        Object[] seen = unguardedTable;
        return seen == null ? 0 : seen.length;
    }

    /**
     * The second twin (#558): the acquire is observed, the release is an {@code int}
     * {@code getAndSet} through the handle (observed since #658), and the thread then replaces
     * {@link #afterRelease} with nothing held. The lock must not outlive its release.
     */
    public boolean growThenWriteAfterUnobservedRelease() {
        boolean won = false;
        if (BUSY.compareAndSet(this, 0, 1)) {
            won = true;
            try {
                table = next(table);
            } finally {
                int ignored = (int) BUSY.getAndSet(this, 0);
            }
            afterRelease = next(afterRelease);
        }
        return afterRelease != null && won;
    }

    /** Replaces the table under the spinlock, released by {@code getAndSet} used as a statement (#658). */
    public int growReleasedByGetAndSetStatement() {
        if (BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                BUSY.getAndSet(this, 0);
            }
        }
        Object[] seen = table;
        return seen == null ? 0 : seen.length;
    }

    /** Replaces the table under the spinlock, released by {@code getAndSetRelease} used as a statement (#667). */
    public int growReleasedByGetAndSetRelease() {
        if (BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                BUSY.getAndSetRelease(this, 0);
            }
        }
        Object[] seen = table;
        return seen == null ? 0 : seen.length;
    }

    /**
     * The twin: {@code getAndSetRelease} releases (woven since #667), and {@link #afterRelease} is
     * then replaced with nothing held.
     */
    public boolean growThenWriteAfterGetAndSetRelease() {
        boolean won = false;
        if (BUSY.compareAndSet(this, 0, 1)) {
            won = true;
            try {
                table = next(table);
            } finally {
                int ignored = (int) BUSY.getAndSetRelease(this, 0);
            }
            afterRelease = next(afterRelease);
        }
        return afterRelease != null && won;
    }

    private static Object[] next(Object[] current) {
        return current == null || current.length >= 64 ? new Object[1]
                : Arrays.copyOf(current, current.length * 2);
    }
}
