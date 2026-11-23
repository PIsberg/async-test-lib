package com.example.agentfixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The {@code long} {@code VarHandle} read-modify-write forms used as statements (#1494).
 *
 * <p>Called as a statement, a signature-polymorphic {@code VarHandle} method compiles with a
 * {@code void} descriptor, so the weaver's hook returns a {@code long} the call site never wanted
 * and the weaver must drop it. A {@code long} takes two stack slots: one {@code POP} leaves half of
 * it behind, and the class fails verification as it loads. Kept apart from {@link AtomicFlagBean}
 * so a {@code VerifyError} here fails only the tests that load this class.
 */
public class LongHandleStatementBean {

    private volatile long gauge;
    private volatile int lever;

    static final VarHandle GAUGE;
    static final VarHandle LEVER;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            GAUGE = lookup.findVarHandle(LongHandleStatementBean.class, "gauge", long.class);
            LEVER = lookup.findVarHandle(LongHandleStatementBean.class, "lever", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Each {@code long} form as a statement, from 0; {@return the field after them, then each step} */
    public String longStatements() {
        StringBuilder trace = new StringBuilder();
        GAUGE.getAndAdd(this, 5L);
        trace.append(gauge);
        GAUGE.getAndAddRelease(this, 2L);
        trace.append(',').append(gauge);
        GAUGE.getAndBitwiseOr(this, 8L);
        trace.append(',').append(gauge);
        GAUGE.getAndBitwiseAnd(this, 12L);
        trace.append(',').append(gauge);
        GAUGE.getAndBitwiseXor(this, 3L);
        trace.append(',').append(gauge);
        GAUGE.getAndSet(this, 1L);
        trace.append(',').append(gauge);
        GAUGE.compareAndExchange(this, 1L, 4L);
        trace.append(',').append(gauge);
        return trace.toString();
    }

    /** The same forms on the {@code int} field, the case #658 fixed, as the twin. */
    public String intStatements() {
        StringBuilder trace = new StringBuilder();
        LEVER.getAndAdd(this, 5);
        trace.append(lever);
        LEVER.getAndAddRelease(this, 2);
        trace.append(',').append(lever);
        LEVER.getAndBitwiseOr(this, 8);
        trace.append(',').append(lever);
        LEVER.getAndBitwiseAnd(this, 12);
        trace.append(',').append(lever);
        LEVER.getAndBitwiseXor(this, 3);
        trace.append(',').append(lever);
        LEVER.getAndSet(this, 1);
        trace.append(',').append(lever);
        LEVER.compareAndExchange(this, 1, 4);
        trace.append(',').append(lever);
        return trace.toString();
    }
}
