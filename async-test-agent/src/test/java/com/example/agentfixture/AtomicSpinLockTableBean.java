package com.example.agentfixture;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A volatile table replaced only under a spinlock that is an atomic object rather than a field of
 * the receiver: {@code busy.compareAndSet(false, true)} on an {@link AtomicBoolean}, or
 * {@code compareAndSet(0, 1)} on an {@link AtomicInteger} (#558).
 *
 * <p>The twins acquire through a shape the weaver observes and release through one it does not
 * ({@code compareAndExchange}, {@code decrementAndGet}), then write a second table outside the
 * lock. That write must not read as guarded by a lock the thread no longer holds.
 */
public final class AtomicSpinLockTableBean {

    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicInteger intBusy = new AtomicInteger();

    private volatile Object[] table;
    private volatile Object[] afterRelease;

    /** {@code compareAndSet(false, true)}, released by {@code set(false)}. */
    public int growBooleanReleasedBySet() {
        if (busy.compareAndSet(false, true)) {
            try {
                table = next(table);
            } finally {
                busy.set(false);
            }
        }
        return length(table);
    }

    /** {@code compareAndSet(false, true)}, released by {@code compareAndSet(true, false)}. */
    public int growBooleanReleasedByCompareAndSet() {
        if (busy.compareAndSet(false, true)) {
            try {
                table = next(table);
            } finally {
                busy.compareAndSet(true, false);
            }
        }
        return length(table);
    }

    /** {@code getAndSet(true)} returning {@code false} is a won acquire too; released by {@code lazySet(false)}. */
    public int growBooleanAcquiredByGetAndSet() {
        if (!busy.getAndSet(true)) {
            try {
                table = next(table);
            } finally {
                busy.lazySet(false);
            }
        }
        return length(table);
    }

    /** {@code compareAndSet(0, 1)} on an {@code AtomicInteger}, released by {@code set(0)}. */
    public int growIntegerReleasedBySet() {
        if (intBusy.compareAndSet(0, 1)) {
            try {
                table = next(table);
            } finally {
                intBusy.set(0);
            }
        }
        return length(table);
    }

    /** {@code compareAndSet(0, 1)} on an {@code AtomicInteger}, released by {@code compareAndSet(1, 0)}. */
    public int growIntegerReleasedByCompareAndSet() {
        if (intBusy.compareAndSet(0, 1)) {
            try {
                table = next(table);
            } finally {
                intBusy.compareAndSet(1, 0);
            }
        }
        return length(table);
    }

    /** The twin: an observed acquire, a {@code compareAndExchange} release, then an unguarded write. */
    public int growBooleanThenWriteAfterUnobservedRelease() {
        if (busy.compareAndSet(false, true)) {
            try {
                table = next(table);
            } finally {
                busy.compareAndExchange(true, false);
            }
            afterRelease = next(afterRelease);
        }
        return length(afterRelease);
    }

    /** The twin: an observed acquire, a {@code decrementAndGet} release, then an unguarded write. */
    public int growIntegerThenWriteAfterUnobservedRelease() {
        if (intBusy.compareAndSet(0, 1)) {
            try {
                table = next(table);
            } finally {
                intBusy.decrementAndGet();
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
