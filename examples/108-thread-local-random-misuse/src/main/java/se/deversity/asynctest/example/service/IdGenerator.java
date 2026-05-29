package se.deversity.asynctest.example.service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates unique-ish identifiers backed by a {@link ThreadLocalRandom}.
 *
 * <p><strong>Bug:</strong> {@link ThreadLocalRandom#current()} returns the generator
 * belonging to the calling thread. Caching that reference in a field captures the
 * generator of whichever thread happened to construct this object, then shares it with
 * every other thread that calls {@link #nextId()}. This defeats the per-thread isolation
 * {@code ThreadLocalRandom} relies on: concurrent use of one instance from multiple
 * threads corrupts and biases the produced output (the whole point of the class is that
 * each thread has its own instance).
 *
 * <p><strong>Fix:</strong> Never store the result of {@code ThreadLocalRandom.current()}.
 * Call it afresh on each thread at each use site, e.g.
 * {@code ThreadLocalRandom.current().nextLong()}, so every thread uses its own generator.
 */
public class IdGenerator {

    // BUG: captured once, shared by all threads
    private final ThreadLocalRandom rng = ThreadLocalRandom.current();

    /**
     * Returns the next identifier. Not thread-safe: uses the cached cross-thread RNG.
     */
    public long nextId() {
        return rng.nextLong();
    }

    /** Returns the cached RNG reference for test instrumentation. */
    public ThreadLocalRandom getRng() {
        return rng;
    }
}
