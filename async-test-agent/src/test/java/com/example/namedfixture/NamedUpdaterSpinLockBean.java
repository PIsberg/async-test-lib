package com.example.namedfixture;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * An updater spinlock the test defines in a named module of its own and initialises before the
 * agent attaches (#668).
 *
 * <p>A named module reads nothing it does not require, so its woven call sites cannot link to a
 * library on the class path unless the agent adds that read edge. Nothing in the test refers to
 * this class by type: a reference would load a second copy in the test's own unnamed module.
 */
public final class NamedUpdaterSpinLockBean {

    private static final AtomicIntegerFieldUpdater<NamedUpdaterSpinLockBean> BUSY =
            AtomicIntegerFieldUpdater.newUpdater(NamedUpdaterSpinLockBean.class, "busy");

    private volatile int busy;

    /** Forces initialisation, so the type initializer runs before the agent attaches. */
    public static void initialise() {
        // Nothing to do: calling a static method is what initialises the class.
    }

    /** Takes the spinlock through a call site the weaver substitutes. */
    public boolean acquire() {
        return BUSY.compareAndSet(this, 0, 1);
    }

    /** Releases the spinlock with the swap back. */
    public boolean release() {
        return BUSY.compareAndSet(this, 1, 0);
    }

    /** {@return the flag} */
    public int busy() {
        return busy;
    }
}
