package com.example.agentfixture;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A counter kept as a mutable value in a map, bumped inside the map's own atomic update and
 * outside it (#1245). {@code ConcurrentHashMap} documents the whole of {@code compute},
 * {@code computeIfPresent}, {@code computeIfAbsent} and {@code merge} as atomic for the key, so a
 * value mutated inside the function is guarded; one mutated after a {@code get} is not.
 */
public final class ComputeTallyBean {

    private ComputeTallyBean() {
    }

    /** The mutable value. */
    public static final class Tally {
        int hits;

        public int hits() {
            return hits;
        }
    }

    /** {@code compute} through the {@code Map} interface. */
    public static void compute(Map<String, Tally> map, String key) {
        map.compute(key, (k, tally) -> {
            Tally t = tally == null ? new Tally() : tally;
            t.hits++;
            return t;
        });
    }

    /** {@code compute} called on the concrete class, an invokevirtual call site. */
    public static void computeOnTheClass(ConcurrentHashMap<String, Tally> map, String key) {
        map.compute(key, (k, tally) -> {
            Tally t = tally == null ? new Tally() : tally;
            t.hits++;
            return t;
        });
    }

    /** {@code computeIfPresent}. */
    public static void computeIfPresent(Map<String, Tally> map, String key) {
        map.computeIfPresent(key, (k, tally) -> {
            tally.hits++;
            return tally;
        });
    }

    /** {@code merge}, bumping the existing value in the remapping function. */
    public static void merge(Map<String, Tally> map, String key) {
        map.merge(key, new Tally(), (existing, fresh) -> {
            existing.hits++;
            return existing;
        });
    }

    /** The broken twin: the read and the bump are two steps, and nothing holds them together. */
    public static void getThenBump(Map<String, Tally> map, String key) {
        map.get(key).hits++;
    }
}
