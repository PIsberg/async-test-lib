package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * An updater spinlock the test loads through its own child-first classloader, together with a
 * private copy of the library, and initialises before the agent attaches (#659).
 *
 * <p>Its woven call sites reach the isolated loader's {@code TelemetryRegistry}, not the copy the
 * agent's own loader sees, so whatever resolves {@code BUSY} has to work in that copy. Nothing in
 * the test refers to this class by type: a reference would load a second copy in the test's loader.
 */
public final class IsolatedUpdaterSpinLockBean {

    private static final AtomicIntegerFieldUpdater<IsolatedUpdaterSpinLockBean> BUSY =
            AtomicIntegerFieldUpdater.newUpdater(IsolatedUpdaterSpinLockBean.class, "busy");

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
