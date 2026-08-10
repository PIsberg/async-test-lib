package se.deversity.asynctest.example.service;

import java.util.SplittableRandom;

/**
 * Generates randomized think-time for simulated users in a load test harness.
 *
 * <p>The buggy shape looks like a sensible economy — one generator, shared:
 *
 * <pre>{@code
 * private static final SplittableRandom RNG = new SplittableRandom(SEED);
 *
 * long thinkTimeMillis() {
 *     return RNG.nextLong(50, 500);   // BUG: called from every worker thread
 * }
 * }</pre>
 *
 * <p>{@code SplittableRandom}'s own Javadoc: "Instances of SplittableRandom are not
 * thread-safe." Its state transition is a plain, non-atomic read-modify-write. Two workers
 * calling {@code nextLong()} at the same time interleave that transition — duplicated values,
 * lost state advances, broken statistical guarantees. Nothing throws. The load test keeps
 * running; its randomness quietly stops being random, and with it the reproducibility the
 * seed was supposed to buy.
 *
 * <p>This is a different defect from sharing a {@code java.util.Random}: {@code Random} is
 * thread-safe but contended (every call CASes one shared seed), which is
 * {@code SHARED_RANDOM}'s finding. Here there is no safety to contend on.
 *
 * <p>The fix is the API's own design — the name says it:
 *
 * <pre>{@code
 * SplittableRandom perWorker = root.split();   // FIX: each worker gets its own
 * }</pre>
 *
 * <p>{@code split()} derives an independent, statistically uncorrelated child generator.
 * Split once per worker on the coordinating thread, hand each worker its child, and both
 * thread-safety and per-seed reproducibility hold.
 */
public final class LoadSimulator {

    private final SplittableRandom root;

    public LoadSimulator(long seed) {
        this.root = new SplittableRandom(seed);
    }

    /** BUG: every worker calls into this one instance. */
    public SplittableRandom sharedGenerator() {
        return root;
    }

    /** FIX: an independent child generator to hand one worker. */
    public SplittableRandom generatorForWorker() {
        return root.split();
    }

    /** Think-time drawn from the given generator — [50, 500) ms. */
    public long thinkTimeMillis(SplittableRandom generator) {
        return generator.nextLong(50, 500);
    }
}
