package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts page hits from inside a callback lambda, three ways.
 *
 * <p>The captured {@code int[]} is the workaround everyone reaches for when the compiler refuses
 * a plain {@code int} - the array reference is effectively final, so it compiles, and the
 * contents are as shared and as unguarded as any other field.
 */
public final class HitCounter {

    /** The captured container. Effectively final by reference, wide open by contents. */
    private final int[] hits = {0};

    /** The monitor the guarded variant holds across the whole read-modify-write. */
    private final Object guard = new Object();

    private final AtomicInteger atomicHits = new AtomicInteger();

    /** {@return the value currently in the captured array} */
    public int read() {
        return hits[0];
    }

    /** Writes a value computed elsewhere back into the captured array. */
    public void write(int value) {
        hits[0] = value;
    }

    /** {@return the monitor callers must hold for the guarded variant} */
    public Object guard() {
        return guard;
    }

    /** The atomic alternative: one instruction, no window between the read and the write. */
    public int incrementAtomically() {
        return atomicHits.incrementAndGet();
    }

    /** {@return the atomic counter's current value} */
    public int atomicValue() {
        return atomicHits.get();
    }
}
