package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
