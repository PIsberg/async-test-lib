package com.example.agentfixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * {@link SpinLockTableBean}'s spinlock, in a class the test initialises before the agent attaches
 * (#558).
 *
 * <p>Its type initializer has already run by the time the weaver sees it, so the call that tells
 * the registry which field {@code BUSY} is a handle on never executes. The call sites are still
 * woven on retransformation, and the handle has to be resolved from the handle itself.
 */
public final class PreAttachSpinLockTableBean {

    private static final VarHandle BUSY;

    static {
        try {
            BUSY = MethodHandles.lookup().findVarHandle(PreAttachSpinLockTableBean.class, "busy",
                    int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile Object[] table;
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

    private static Object[] next(Object[] current) {
        return current == null || current.length >= 64 ? new Object[1]
                : Arrays.copyOf(current, current.length * 2);
    }
}
