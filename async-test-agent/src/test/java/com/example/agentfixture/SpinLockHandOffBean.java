package com.example.agentfixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;

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
}
