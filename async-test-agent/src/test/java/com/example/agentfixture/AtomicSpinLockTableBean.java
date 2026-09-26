package com.example.agentfixture;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A volatile table replaced only under a spinlock that is an atomic object rather than a field of
 * the receiver: {@code busy.compareAndSet(false, true)} on an {@link AtomicBoolean}, or
 * {@code compareAndSet(0, 1)} on an {@link AtomicInteger} (#558).
 *
 * <p>The twins acquire through a shape the weaver observes, release, then write a second table
 * outside the lock. That write must not read as guarded by a lock the thread no longer holds,
 * whichever form the release takes ({@code compareAndExchange}, {@code decrementAndGet}, #658;
 * {@code setPlain}, {@code updateAndGet}, #667).
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
    public boolean growBooleanThenWriteAfterUnobservedRelease() {
        boolean won = false;
        if (busy.compareAndSet(false, true)) {
            won = true;
            try {
                table = next(table);
            } finally {
                busy.compareAndExchange(true, false);
            }
            afterRelease = next(afterRelease);
        }
        return afterRelease != null && won;
    }

    /** The twin: an observed acquire, a {@code decrementAndGet} release, then an unguarded write. */
    public boolean growIntegerThenWriteAfterUnobservedRelease() {
        boolean won = false;
        if (intBusy.compareAndSet(0, 1)) {
            won = true;
            try {
                table = next(table);
            } finally {
                intBusy.decrementAndGet();
            }
            afterRelease = next(afterRelease);
        }
        return afterRelease != null && won;
    }

    /** {@code compareAndSet(0, 1)} on an {@code AtomicInteger}, released by {@code decrementAndGet()} (#658). */
    public int growIntegerReleasedByDecrementAndGet() {
        if (intBusy.compareAndSet(0, 1)) {
            try {
                table = next(table);
            } finally {
                intBusy.decrementAndGet();
            }
        }
        return length(table);
    }

    /** {@code compareAndSet(false, true)}, released by {@code compareAndExchange(true, false)} (#658). */
    public int growBooleanReleasedByCompareAndExchange() {
        if (busy.compareAndSet(false, true)) {
            try {
                table = next(table);
            } finally {
                busy.compareAndExchange(true, false);
            }
        }
        return length(table);
    }

    /** {@code compareAndSet(false, true)}, released by {@code setPlain(false)} (#667). */
    public int growBooleanReleasedBySetPlain() {
        if (busy.compareAndSet(false, true)) {
            try {
                table = next(table);
            } finally {
                busy.setPlain(false);
            }
        }
        return length(table);
    }

    /** {@code compareAndSet(0, 1)} on an {@code AtomicInteger}, released by {@code updateAndGet} (#667). */
    public int growIntegerReleasedByUpdateAndGet() {
        if (intBusy.compareAndSet(0, 1)) {
            try {
                table = next(table);
            } finally {
                intBusy.updateAndGet(held -> 0);
            }
        }
        return length(table);
    }

    /** The twin: a {@code setPlain(false)} release (woven since #667), then an unguarded write. */
    public boolean growBooleanThenWriteAfterSetPlain() {
        boolean won = false;
        if (busy.compareAndSet(false, true)) {
            won = true;
            try {
                table = next(table);
            } finally {
                busy.setPlain(false);
            }
            afterRelease = next(afterRelease);
        }
        return afterRelease != null && won;
    }

    /** The twin: an {@code updateAndGet} release (woven since #667), then an unguarded write. */
    public boolean growIntegerThenWriteAfterUpdateAndGet() {
        boolean won = false;
        if (intBusy.compareAndSet(0, 1)) {
            won = true;
            try {
                table = next(table);
            } finally {
                intBusy.updateAndGet(held -> 0);
            }
            afterRelease = next(afterRelease);
        }
        return afterRelease != null && won;
    }

    private static int length(Object[] seen) {
        return seen == null ? 0 : seen.length;
    }

    private static Object[] next(Object[] current) {
        return current == null || current.length >= 64 ? new Object[1]
                : Arrays.copyOf(current, current.length * 2);
    }
}
