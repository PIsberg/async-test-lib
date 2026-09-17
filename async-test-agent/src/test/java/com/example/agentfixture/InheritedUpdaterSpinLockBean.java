package com.example.agentfixture;

import com.example.unwovenfixture.UnwovenUpdaterBase;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * A woven subclass with its own updater spinlock, extending a superclass the weaver never scans
 * that has another (#619).
 *
 * <p>The test initialises it before the agent attaches, so neither updater's binding call runs
 * woven. #619 resolved such updaters from the fields recorded in the receiver's hierarchy, where
 * only {@code busy} is ever recorded; a swap through {@code STATE} named as {@code busy} would
 * declare a lock on a flag the swap never touched. Since #659 each resolves from its own target
 * class and offset: {@code STATE} to {@code state}, {@code BUSY} to {@code busy}.
 */
public final class InheritedUpdaterSpinLockBean extends UnwovenUpdaterBase {

    private static final AtomicIntegerFieldUpdater<InheritedUpdaterSpinLockBean> BUSY =
            AtomicIntegerFieldUpdater.newUpdater(InheritedUpdaterSpinLockBean.class, "busy");

    private volatile int busy;

    /** Forces initialisation, so the type initializer runs before the agent attaches. */
    public static void initialise() {
        // Nothing to do: calling a static method is what initialises the class.
    }

    /** Takes the superclass's spinlock, through a call site the weaver substitutes. */
    public boolean acquireState() {
        return STATE.compareAndSet(this, 0, 1);
    }

    /** Releases the superclass's spinlock. */
    public void releaseState() {
        STATE.compareAndSet(this, 1, 0);
    }

    /** Takes this class's own spinlock. */
    public boolean acquireBusy() {
        return BUSY.compareAndSet(this, 0, 1);
    }

    /** Releases this class's own spinlock. */
    public void releaseBusy() {
        BUSY.compareAndSet(this, 1, 0);
    }
}