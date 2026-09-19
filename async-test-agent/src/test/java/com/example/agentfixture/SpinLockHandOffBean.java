package com.example.agentfixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Spinlocks taken with a swap the weaver observes and released through a value-returning form
 * (#658), each step its own method so a test can interleave a holder and a contender exactly.
 */
public final class SpinLockHandOffBean {

    private static final VarHandle BUSY;

    static {
        try {
            BUSY = MethodHandles.lookup().findVarHandle(SpinLockHandOffBean.class, "busy", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AtomicInteger intBusy = new AtomicInteger();
    private volatile int busy;

    /** {@return the flag the handle swaps} */
    public int busy() {
        return busy;
    }

    /** The atomic the integer spinlock swaps, which is also the lock's identity. */
    public AtomicInteger intLock() {
        return intBusy;
    }

    /** {@code compareAndSet(0, 1)} on the {@code AtomicInteger}. */
    public boolean acquireInteger() {
        return intBusy.compareAndSet(0, 1);
    }

    /** Releases the {@code AtomicInteger} with {@code decrementAndGet()}. */
    public int releaseIntegerByDecrementAndGet() {
        return intBusy.decrementAndGet();
    }

    /** {@code VarHandle.compareAndSet(this, 0, 1)} on {@code busy}. */
    public boolean acquireHandle() {
        return BUSY.compareAndSet(this, 0, 1);
    }

    /** Releases {@code busy} with {@code VarHandle.getAndSet(this, 0)} used as a statement. */
    public void releaseHandleByGetAndSet() {
        BUSY.getAndSet(this, 0);
    }

    // ---- Releases woven since #667 -------------------------------------------------------------

    private static final AtomicIntegerFieldUpdater<SpinLockHandOffBean> UPDATER_BUSY =
            AtomicIntegerFieldUpdater.newUpdater(SpinLockHandOffBean.class, "updaterBusy");

    private final AtomicBoolean booleanBusy = new AtomicBoolean();
    private volatile int updaterBusy;

    /** The atomic the boolean spinlock swaps, which is also the lock's identity. */
    public AtomicBoolean booleanLock() {
        return booleanBusy;
    }

    /** {@code compareAndSet(false, true)} on the {@code AtomicBoolean}. */
    public boolean acquireBoolean() {
        return booleanBusy.compareAndSet(false, true);
    }

    /** Releases the {@code AtomicBoolean} with {@code setPlain(false)}. */
    public void releaseBooleanBySetPlain() {
        booleanBusy.setPlain(false);
    }

    /** Releases the {@code AtomicInteger} with {@code getAndUpdate}, whose new value the call site never sees. */
    public int releaseIntegerByGetAndUpdate() {
        return intBusy.getAndUpdate(held -> 0);
    }

    /** Releases the {@code AtomicInteger} with {@code accumulateAndGet}. */
    public int releaseIntegerByAccumulateAndGet() {
        return intBusy.accumulateAndGet(0, (held, zero) -> zero);
    }

    /** Releases {@code busy} with {@code VarHandle.getAndSetRelease(this, 0)} used as a statement. */
    public void releaseHandleByGetAndSetRelease() {
        BUSY.getAndSetRelease(this, 0);
    }

    /** Releases {@code busy} with {@code VarHandle.getAndBitwiseAnd(this, 0)}, keeping the previous value. */
    public int releaseHandleByGetAndBitwiseAnd() {
        return (int) BUSY.getAndBitwiseAnd(this, 0);
    }

    /** Releases {@code busy} with {@code VarHandle.compareAndExchangeRelease(this, 1, 0)}. */
    public int releaseHandleByCompareAndExchangeRelease() {
        return (int) BUSY.compareAndExchangeRelease(this, 1, 0);
    }

    /** {@code compareAndSet(this, 0, 1)} on {@code updaterBusy} through an updater. */
    public boolean acquireUpdater() {
        return UPDATER_BUSY.compareAndSet(this, 0, 1);
    }

    /** Releases {@code updaterBusy} with {@code getAndUpdate} through the updater. */
    public int releaseUpdaterByGetAndUpdate() {
        return UPDATER_BUSY.getAndUpdate(this, held -> 0);
    }
}
