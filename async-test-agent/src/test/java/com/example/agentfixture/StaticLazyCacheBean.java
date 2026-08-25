package com.example.agentfixture;

/**
 * A static single-check cache whose published value then goes quiet.
 *
 * <p>The silent half of the {@code PUTSTATIC} pair (#337). {@code if (CACHE == null) CACHE =
 * new Snapshot(...)} at class scope is the same idiom jackson's serializer caches use on an
 * instance field, one level up, and it is safe for the same reason: two threads can miss
 * together and one store is lost, costing one extra object and nothing else, because what was
 * published is never written again.
 *
 * <p>The field is deliberately not {@code volatile} and the store is deliberately unguarded.
 * That is the point: the access stream has to look exactly like a lost update, so that what
 * separates this from a real defect is only the evidence about the stored value.
 *
 * <p>{@code afterMiss} exists so a test does not have to hope for the interleaving. Both threads
 * park there after reading {@code null} and before storing, which makes the double miss
 * deterministic; passing a no-op gives the ordinary single-threaded path.
 */
public final class StaticLazyCacheBean {

    /**
     * The payload. Its only field is written by the constructor and never again, which is what
     * an effectively immutable value looks like in an access stream. The weaver skips
     * constructor writes on purpose - an object still being constructed cannot have escaped -
     * so this object contributes no writes at all after publication.
     */
    public static final class Snapshot {
        private int size;

        Snapshot(int size) {
            this.size = size;
        }

        /** {@return the size this snapshot was built with} */
        public int size() {
            return size;
        }
    }

    private static Snapshot cache;

    private StaticLazyCacheBean() {
    }

    /**
     * Reads the cache and, on a miss, fills it.
     *
     * @param size      the size to build a missing snapshot with
     * @param afterMiss run after the miss check and before the store, so a test can force both
     *                  threads to miss
     * @return the snapshot this caller ended up with
     */
    public static Snapshot lookup(int size, Runnable afterMiss) {
        Snapshot local = cache;
        if (local == null) {
            afterMiss.run();
            local = new Snapshot(size);
            cache = local;
        }
        return local;
    }

    /** {@return the cached snapshot without filling it} */
    public static Snapshot peek() {
        return cache;
    }

    /** Drops the cache so each test starts from a miss. */
    public static void reset() {
        cache = null;
    }
}
