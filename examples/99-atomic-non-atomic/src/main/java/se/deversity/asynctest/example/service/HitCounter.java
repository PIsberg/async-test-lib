package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts hits using an {@link AtomicInteger}.
 *
 * <p><strong>Bug:</strong> {@link #increment()} uses {@code counter.set(counter.get() + 1)}.
 * The {@code get()} and {@code set()} calls are individually atomic but the compound
 * read-modify-write sequence is not. Two threads can both read the same value, each
 * compute {@code value + 1}, and both write the same new value — silently losing
 * one of the increments.
 *
 * <p><strong>Fix:</strong> Replace the compound operation with {@code counter.incrementAndGet()},
 * which performs the increment atomically using a CAS instruction.
 */
public class HitCounter {

    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Increments the hit counter.
     * Bug: non-atomic compound get+set — loses increments under concurrency.
     */
    public void increment() {
        // BUG: counter.get() and counter.set() are individually atomic but not together
        counter.set(counter.get() + 1);
    }

    /**
     * Returns the current hit count.
     */
    public int getCount() {
        return counter.get();
    }

    /**
     * Resets the counter to zero.
     */
    public void reset() {
        counter.set(0);
    }

    /** Returns the raw AtomicInteger for test instrumentation. */
    public AtomicInteger getCounter() {
        return counter;
    }
}
