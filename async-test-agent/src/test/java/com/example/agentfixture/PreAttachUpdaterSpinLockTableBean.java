package com.example.agentfixture;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * {@link UpdaterSpinLockTableBean}'s spinlock, in a class the test initialises before the agent
 * attaches (#619).
 *
 * <p>Its type initializer has already run by the time the weaver sees it, so the call that tells
 * the registry which field {@code BUSY} is an updater on never executes. The call sites are still
 * woven on retransformation, and the updater has to be resolved from its own target class and
 * field offset (#659).
 */
public final class PreAttachUpdaterSpinLockTableBean {

    private static final AtomicIntegerFieldUpdater<PreAttachUpdaterSpinLockTableBean> BUSY =
            AtomicIntegerFieldUpdater.newUpdater(PreAttachUpdaterSpinLockTableBean.class, "busy");

    private volatile Object[] table;
    private volatile Object[] afterRelease;
    private volatile int busy;

    /** Forces initialisation, so the type initializer runs before the agent attaches. */
    public static void initialise() {
        // Nothing to do: calling a static method is what initialises the class.
    }

    /** Replaces the table under the spinlock, released by writing 0 back. */
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

    /**
     * The released-then-unguarded twin: {@code getAndSet} releases (observed since #658), and
     * {@link #afterRelease} is replaced with nothing held. A resolved pre-attach updater must not
     * make that look guarded.
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
        Object[] seen = afterRelease;
        return seen == null ? 0 : seen.length;
    }

    /** Replaces the table under the spinlock, released by {@code getAndUpdate} (#667). */
    public int growReleasedByGetAndUpdate() {
        if (BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                BUSY.getAndUpdate(this, held -> 0);
            }
        }
        Object[] seen = table;
        return seen == null ? 0 : seen.length;
    }

    /**
     * The twin: {@code getAndUpdate} releases (woven since #667), and {@link #afterRelease} is
     * replaced with nothing held.
     */
    public int growThenWriteAfterGetAndUpdate() {
        if (BUSY.compareAndSet(this, 0, 1)) {
            try {
                table = next(table);
            } finally {
                BUSY.getAndUpdate(this, held -> 0);
            }
            afterRelease = next(afterRelease);
        }
        Object[] seen = afterRelease;
        return seen == null ? 0 : seen.length;
    }

    private static Object[] next(Object[] current) {
        return current == null || current.length >= 64 ? new Object[1]
                : Arrays.copyOf(current, current.length * 2);
    }
}
