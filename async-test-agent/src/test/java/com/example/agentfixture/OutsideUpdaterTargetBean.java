package com.example.agentfixture;

import com.example.unwovenfixture.OutsideUpdaterMaker;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * A woven class with its own updater spinlock on {@code busy}, whose {@code state} field an
 * unscanned class outside its hierarchy also makes an updater on (#659).
 *
 * <p>The test initialises both before the agent attaches, so neither binding call runs woven. A
 * swap through {@link OutsideUpdaterMaker#STATE} named as {@code busy} would declare a lock on a
 * flag the swap never touched, and share it with every real {@code busy} holder.
 */
public final class OutsideUpdaterTargetBean {

    private static final AtomicIntegerFieldUpdater<OutsideUpdaterTargetBean> BUSY =
            AtomicIntegerFieldUpdater.newUpdater(OutsideUpdaterTargetBean.class, "busy");

    /** Public so {@link OutsideUpdaterMaker} can make an updater on it. */
    public volatile int state;

    private volatile int busy;

    /** Forces initialisation, so the type initializer runs before the agent attaches. */
    public static void initialise() {
        // Nothing to do: calling a static method is what initialises the class.
    }

    /** Takes the spinlock on {@code state} through the outside updater, at a woven call site. */
    public boolean acquireOutside() {
        return OutsideUpdaterMaker.STATE.compareAndSet(this, 0, 1);
    }

    /** Releases the spinlock on {@code state}. */
    public boolean releaseOutside() {
        return OutsideUpdaterMaker.STATE.compareAndSet(this, 1, 0);
    }

    /** Takes this class's own spinlock on {@code busy}. */
    public boolean acquireBusy() {
        return BUSY.compareAndSet(this, 0, 1);
    }

    /** Releases this class's own spinlock. */
    public boolean releaseBusy() {
        return BUSY.compareAndSet(this, 1, 0);
    }

    /** {@return the flag {@code BUSY} swaps} */
    public int busy() {
        return busy;
    }
}
