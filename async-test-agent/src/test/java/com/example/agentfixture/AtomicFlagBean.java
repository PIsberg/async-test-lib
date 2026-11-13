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

    /** Updates the data having read no atomic: nothing orders it after the writer. */
    public int bumpWithoutAFlag() {
        data = data + 1;
        return data;
    }
}
