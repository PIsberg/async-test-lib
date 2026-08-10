package se.deversity.asynctest.example.service;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Counts requests per window for a rate limiter.
 *
 * <p>Someone reached for {@link VarHandle} here because {@code AtomicInteger} "allocates", and
 * the counter is on the hot path of every request. That reasoning is fine. What went wrong is
 * the operation they picked:
 *
 * <pre>{@code
 * int current = (int) COUNT.getVolatile(this);
 * COUNT.setVolatile(this, current + 1);          // BUG: two operations, not one
 * }</pre>
 *
 * <p>Both halves are volatile, so it is easy to believe the pair is atomic. It is not. A
 * volatile read and a volatile write are each individually indivisible, and the gap between
 * them is wide open. Two threads reading 5 both write 6, and one request slips through the
 * limiter unmetered. Volatile buys visibility, never atomicity of a read-modify-write.
 *
 * <p>{@code VarHandle} has the right operation built in — {@code getAndAdd}, or a
 * {@code compareAndSet} retry loop when the new value depends on more than addition. Both are
 * single indivisible operations on the same field, at the same cost the author wanted.
 *
 * <p>The plain-mode accessors are worth a separate mention. {@code get}/{@code set} in
 * {@link VarHandle.AccessMode#GET} order carry no memory-ordering guarantee at all, so a
 * writer's value may never become visible to a reader on another thread. That is a different
 * bug from the lost update and the detector reports it separately.
 */
public final class RateLimiter {

    private static final VarHandle COUNT;
    private static final VarHandle PLAIN_FLAG;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            COUNT = lookup.findVarHandle(RateLimiter.class, "count", int.class);
            PLAIN_FLAG = lookup.findVarHandle(RateLimiter.class, "flag", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused")   // read and written through COUNT
    private volatile int count;

    @SuppressWarnings("unused")   // read and written through PLAIN_FLAG
    private volatile boolean flag;

    public static VarHandle countHandle() {
        return COUNT;
    }

    public static VarHandle plainFlagHandle() {
        return PLAIN_FLAG;
    }

    public int count() {
        return (int) COUNT.getVolatile(this);
    }

    /** BUG: getVolatile then setVolatile — two operations, so updates are lost. */
    public void recordRequestNonAtomically() {
        int current = (int) COUNT.getVolatile(this);
        COUNT.setVolatile(this, current + 1);
    }

    /** The fix: one indivisible operation on the same field. */
    public void recordRequest() {
        COUNT.getAndAdd(this, 1);
    }

    /**
     * The fix when the new value is not a simple sum — a CAS retry loop. Still indivisible:
     * the write only lands if nobody moved the value since the read.
     */
    public void recordRequestCapped(int ceiling) {
        int current;
        int next;
        do {
            current = (int) COUNT.getVolatile(this);
            next = Math.min(current + 1, ceiling);
        } while (!COUNT.compareAndSet(this, current, next));
    }

    /** BUG: plain mode has no ordering, so this write may never be seen by another thread. */
    public void openGateWithPlainWrite() {
        PLAIN_FLAG.set(this, true);
    }

    public boolean gateOpenPlainRead() {
        return (boolean) PLAIN_FLAG.get(this);
    }
}
