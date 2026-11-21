package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * A plain field published by a primitive atomic used as a completion flag (#1315).
 *
 * <p>Each {@code publish} method updates {@code data} and then stores into an atomic; each
 * {@code bumpAfter} method reads that atomic and updates {@code data} only when it saw the stored
 * value. The store is a volatile write and the read a volatile read, so the two updates are ordered.
 */
public class AtomicFlagBean {

    public int data;

    public final AtomicBoolean done = new AtomicBoolean();

    public final AtomicInteger count = new AtomicInteger();

    public final AtomicLong total = new AtomicLong();

    /** A volatile field reached both directly and through {@link #GATE} (#1317). */
    public volatile int gate;

    static final AtomicIntegerFieldUpdater<AtomicFlagBean> GATE =
            AtomicIntegerFieldUpdater.newUpdater(AtomicFlagBean.class, "gate");

    /** A volatile int reached both directly and through {@link #LEVER}, a VarHandle (#1275, #1317). */
    public volatile int lever;

    static final java.lang.invoke.VarHandle LEVER;

    static {
        try {
            LEVER = java.lang.invoke.MethodHandles.lookup().findVarHandle(AtomicFlagBean.class, "lever", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public void publishThroughHandleSetVolatile() {
        data = data + 1;
        LEVER.setVolatile(this, 5);
    }

    public void publishThroughHandleSetRelease() {
        data = data + 1;
        LEVER.setRelease(this, 5);
    }

    public void publishThroughHandleCompareAndSet() {
        data = data + 1;
        LEVER.compareAndSet(this, 0, 5);
    }

    public void publishThroughHandleGetAndAdd() {
        data = data + 1;
        int ignored = (int) LEVER.getAndAdd(this, 5);
    }

    /** A plain store: no release, so it publishes nothing. */
    public void publishThroughHandlePlainSet() {
        data = data + 1;
        LEVER.set(this, 5);
    }

    public void publishThroughDirectLeverWrite() {
        data = data + 1;
        lever = 5;
    }

    public int bumpAfterHandleGetVolatile() {
        if ((int) LEVER.getVolatile(this) < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterHandleGetAcquire() {
        if ((int) LEVER.getAcquire(this) < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterDirectLeverRead() {
        if (lever < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** The handle's arithmetic and bitwise forms on {@link #lever} from 0, joined (#1317). */
    public String handleArithmeticTrace() {
        return (int) LEVER.getAndAdd(this, 5) + "," + (int) LEVER.getAndAddRelease(this, 2)
                + "," + (int) LEVER.getAndBitwiseOr(this, 8) + "," + (int) LEVER.getAndBitwiseAnd(this, 12)
                + "," + (int) LEVER.getAndBitwiseXor(this, 3) + "," + (int) LEVER.getAndSet(this, 1)
                + "," + (int) LEVER.compareAndExchange(this, 1, 4) + "," + (int) LEVER.getVolatile(this);
    }

    /** A volatile long reached both directly and through {@link #TALLY} (#1317). */
    public volatile long tally;

    static final AtomicLongFieldUpdater<AtomicFlagBean> TALLY =
            AtomicLongFieldUpdater.newUpdater(AtomicFlagBean.class, "tally");

    public void publishThroughLongUpdaterSet() {
        data = data + 1;
        TALLY.set(this, 5L);
    }

    public void publishThroughLongUpdaterCompareAndSet() {
        data = data + 1;
        TALLY.compareAndSet(this, 0L, 5L);
    }

    public void publishThroughLongUpdaterAddAndGet() {
        data = data + 1;
        TALLY.addAndGet(this, 5L);
    }

    public void publishThroughLongUpdaterUpdateAndGet() {
        data = data + 1;
        TALLY.updateAndGet(this, t -> t + 5L);
    }

    public void publishThroughDirectLongVolatileWrite() {
        data = data + 1;
        tally = 5L;
    }

    public int bumpAfterLongUpdaterGet() {
        if (TALLY.get(this) < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterDirectLongVolatileRead() {
        if (tally < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Every long updater form on {@link #tally} from 0, joined: what the woven loops must return. */
    public String longUpdaterArithmeticTrace() {
        return TALLY.addAndGet(this, 5L) + "," + TALLY.getAndAdd(this, 2L) + "," + TALLY.incrementAndGet(this)
                + "," + TALLY.getAndIncrement(this) + "," + TALLY.decrementAndGet(this)
                + "," + TALLY.getAndDecrement(this) + "," + TALLY.updateAndGet(this, t -> t * 2L)
                + "," + TALLY.getAndUpdate(this, t -> t + 1L) + "," + TALLY.accumulateAndGet(this, 3L, Long::sum)
                + "," + TALLY.getAndAccumulate(this, 2L, (a, b) -> a * b) + "," + TALLY.getAndSet(this, 1L)
                + "," + TALLY.compareAndSet(this, 1L, 4L) + "," + TALLY.get(this);
    }

    // --- An AtomicIntegerFieldUpdater slot, and the same field read or written directly (#1317) ---

    public void publishThroughUpdaterSet() {
        data = data + 1;
        GATE.set(this, 5);
    }

    public void publishThroughUpdaterLazySet() {
        data = data + 1;
        GATE.lazySet(this, 5);
    }

    public void publishThroughUpdaterCompareAndSet() {
        data = data + 1;
        GATE.compareAndSet(this, 0, 5);
    }

    public void publishThroughUpdaterGetAndSet() {
        data = data + 1;
        GATE.getAndSet(this, 5);
    }

    public void publishThroughUpdaterAddAndGet() {
        data = data + 1;
        GATE.addAndGet(this, 5);
    }

    public void publishThroughUpdaterIncrementAndGet() {
        data = data + 1;
        GATE.incrementAndGet(this);
    }

    public void publishThroughUpdaterUpdateAndGet() {
        data = data + 1;
        GATE.updateAndGet(this, g -> g + 5);
    }

    public void publishThroughDirectVolatileWrite() {
        data = data + 1;
        gate = 5;
    }

    /** The gate first and the update after it: the gate publishes nothing of the update. */
    public void setTheUpdaterGateBeforeTheUpdate() {
        GATE.set(this, 5);
        data = data + 1;
    }

    public int bumpAfterUpdaterGet() {
        if (GATE.get(this) < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Every updater form on {@link #gate} from 0, joined: what the woven loops must return (#1317). */
    public String updaterArithmeticTrace() {
        return GATE.addAndGet(this, 5) + "," + GATE.getAndAdd(this, 2) + "," + GATE.incrementAndGet(this)
                + "," + GATE.getAndIncrement(this) + "," + GATE.decrementAndGet(this)
                + "," + GATE.getAndDecrement(this) + "," + GATE.updateAndGet(this, g -> g * 2)
                + "," + GATE.getAndUpdate(this, g -> g + 1) + "," + GATE.accumulateAndGet(this, 3, Integer::sum)
                + "," + GATE.getAndAccumulate(this, 2, (a, b) -> a * b) + "," + GATE.getAndSet(this, 1)
                + "," + GATE.compareAndSet(this, 1, 4) + "," + GATE.get(this);
    }

    public int bumpAfterDirectVolatileRead() {
        if (gate < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public void publishThroughBooleanSet() {
        data = data + 1;
        done.set(true);
    }

    public void publishThroughBooleanCompareAndSet() {
        data = data + 1;
        done.compareAndSet(false, true);
    }

    public void publishThroughBooleanGetAndSet() {
        data = data + 1;
        done.getAndSet(true);
    }

    /** The flag first and the update after it: the flag publishes nothing of the update. */
    public void setTheFlagBeforeTheUpdate() {
        done.set(true);
        data = data + 1;
    }

    public int bumpAfterBoolean() {
        if (!done.get()) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Reads the flag still unset, a value no thread stored, and updates the data anyway. */
    public int bumpAfterSeeingTheFlagUnset() {
        if (done.get()) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public void publishThroughIntSet() {
        data = data + 1;
        count.set(5);
    }

    public void publishThroughIntIncrement() {
        data = data + 1;
        count.incrementAndGet();
    }

    public void publishThroughIntCompareAndSet() {
        data = data + 1;
        count.compareAndSet(0, 3);
    }

    public int bumpAfterCount() {
        if (count.get() < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public void publishThroughLongSet() {
        data = data + 1;
        total.set(5L);
    }

    public void publishThroughLongAdd() {
        data = data + 1;
        total.addAndGet(2L);
    }

    public int bumpAfterTotal() {
        if (total.get() < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    // --- The acquiring reads besides get (#1317): getAcquire, intValue and longValue ---

    public int bumpAfterBooleanGetAcquire() {
        if (!done.getAcquire()) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterCountGetAcquire() {
        if (count.getAcquire() < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterCountIntValue() {
        if (count.intValue() < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterCountLongValue() {
        if (count.longValue() < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterTotalGetAcquire() {
        if (total.getAcquire() < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterTotalLongValue() {
        if (total.longValue() < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    public int bumpAfterTotalIntValue() {
        if (total.intValue() < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    // --- The rest of the store forms, and the reads that return a stored value (#1317) ---

    public void publishThroughBooleanLazySet() {
        data = data + 1;
        done.lazySet(true);
    }

    public void publishThroughBooleanSetRelease() {
        data = data + 1;
        done.setRelease(true);
    }

    public void publishThroughBooleanCompareAndExchange() {
        data = data + 1;
        done.compareAndExchange(false, true);
    }

    public void publishThroughBooleanWeakCompareAndSetVolatile() {
        data = data + 1;
        while (!done.weakCompareAndSetVolatile(false, true)) {
            Thread.onSpinWait();
        }
    }

    public void publishThroughBooleanWeakCompareAndSetRelease() {
        data = data + 1;
        while (!done.weakCompareAndSetRelease(false, true)) {
            Thread.onSpinWait();
        }
    }

    public void publishThroughIntLazySet() {
        data = data + 1;
        count.lazySet(5);
    }

    public void publishThroughIntSetRelease() {
        data = data + 1;
        count.setRelease(5);
    }

    public void publishThroughIntCompareAndExchange() {
        data = data + 1;
        count.compareAndExchange(0, 3);
    }

    public void publishThroughIntCompareAndExchangeRelease() {
        data = data + 1;
        count.compareAndExchangeRelease(0, 3);
    }

    public void publishThroughIntWeakCompareAndSetVolatile() {
        data = data + 1;
        while (!count.weakCompareAndSetVolatile(0, 3)) {
            Thread.onSpinWait();
        }
    }

    public void publishThroughIntWeakCompareAndSetRelease() {
        data = data + 1;
        while (!count.weakCompareAndSetRelease(0, 3)) {
            Thread.onSpinWait();
        }
    }

    public void publishThroughIntUpdateAndGet() {
        data = data + 1;
        count.updateAndGet(c -> c + 3);
    }

    public void publishThroughIntGetAndUpdate() {
        data = data + 1;
        count.getAndUpdate(c -> c + 3);
    }

    public void publishThroughIntAccumulateAndGet() {
        data = data + 1;
        count.accumulateAndGet(3, Integer::sum);
    }

    public void publishThroughIntGetAndAccumulate() {
        data = data + 1;
        count.getAndAccumulate(3, Integer::sum);
    }

    public void publishThroughLongLazySet() {
        data = data + 1;
        total.lazySet(5L);
    }

    public void publishThroughLongSetRelease() {
        data = data + 1;
        total.setRelease(5L);
    }

    public void publishThroughLongWeakCompareAndSetVolatile() {
        data = data + 1;
        while (!total.weakCompareAndSetVolatile(0L, 3L)) {
            Thread.onSpinWait();
        }
    }

    // --- The AtomicLong forms the weaver did not substitute (#1317) ---

    public void publishThroughLongGetAndSet() {
        data = data + 1;
        total.getAndSet(5L);
    }

    public void publishThroughLongCompareAndExchange() {
        data = data + 1;
        total.compareAndExchange(0L, 3L);
    }

    public void publishThroughLongCompareAndExchangeRelease() {
        data = data + 1;
        total.compareAndExchangeRelease(0L, 3L);
    }

    public void publishThroughLongWeakCompareAndSetRelease() {
        data = data + 1;
        while (!total.weakCompareAndSetRelease(0L, 3L)) {
            Thread.onSpinWait();
        }
    }

    public void publishThroughLongUpdateAndGet() {
        data = data + 1;
        total.updateAndGet(t -> t + 3L);
    }

    public void publishThroughLongGetAndUpdate() {
        data = data + 1;
        total.getAndUpdate(t -> t + 3L);
    }

    public void publishThroughLongAccumulateAndGet() {
        data = data + 1;
        total.accumulateAndGet(3L, Long::sum);
    }

    public void publishThroughLongGetAndAccumulate() {
        data = data + 1;
        total.getAndAccumulate(3L, Long::sum);
    }

    /** Reads the total through the previous value a {@code getAndSet} returns. */
    public int bumpAfterTotalGetAndSet() {
        if (total.getAndSet(9L) < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Reads the total through the witness of an exchange that loses. */
    public int bumpAfterTotalExchange() {
        if (total.compareAndExchange(0L, 0L) < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Reads the total through the witness of an acquiring exchange that loses. */
    public int bumpAfterTotalExchangeAcquire() {
        if (total.compareAndExchangeAcquire(0L, 0L) < 1L) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** A plain store: no release, so it publishes nothing. */
    public void publishThroughIntSetPlain() {
        data = data + 1;
        count.setPlain(5);
    }

    /** Reads the flag through the previous value a {@code getAndSet} returns. */
    public int bumpAfterBooleanGetAndSet() {
        if (!done.getAndSet(true)) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Reads the count through the previous value a {@code getAndSet} returns. */
    public int bumpAfterCountGetAndSet() {
        if (count.getAndSet(9) < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Reads the count through the witness of an exchange that loses. */
    public int bumpAfterCountExchange() {
        if (count.compareAndExchange(0, 0) < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Reads the count through the previous value an identity update returns. */
    public int bumpAfterCountUpdate() {
        if (count.getAndUpdate(c -> c) < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Reads the count through the previous value an add of nothing returns. */
    public int bumpAfterCountAdd() {
        if (count.getAndAdd(0) < 1) {
            return -1;
        }
        data = data + 1;
        return data;
    }

    /** Updates the data having read no atomic: nothing orders it after the writer. */
    public int bumpWithoutAFlag() {
        data = data + 1;
        return data;
    }
}
