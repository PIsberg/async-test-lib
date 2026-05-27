package se.deversity.asynctest.example.service;

import java.util.Random;

/**
 * BUGGY service that demonstrates shared Random misuse.
 *
 * BUG: A single static Random instance is shared across all threads. Although
 *      Random is thread-safe (its nextXxx methods use CAS on an AtomicLong
 *      seed), every concurrent call contends on the same atomic operation.
 *      Under high concurrency this collapses throughput and the seed sequence
 *      becomes predictable via seed-recovery attacks.
 *
 * FIX: Replace RANDOM.nextInt() with ThreadLocalRandom.current().nextInt().
 *      ThreadLocalRandom uses a per-thread seed, eliminating contention and
 *      producing better-distributed sequences under concurrent load.
 */
public class TokenGenerator {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    // BUG: single shared Random instance — all threads contend on seed updates
    private static final Random RANDOM = new Random();

    /**
     * Generate a random alphanumeric token of the given length.
     * Thread-safe in correctness, but all threads compete on the shared RANDOM.
     */
    public String generateToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length()))); // BUG: shared RANDOM
        }
        return sb.toString();
    }

    /** Exposed so tests can register the instance with the detector. */
    public static Random getRandom() {
        return RANDOM;
    }
}
