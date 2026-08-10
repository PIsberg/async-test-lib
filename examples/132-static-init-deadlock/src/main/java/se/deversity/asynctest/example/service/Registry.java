package se.deversity.asynctest.example.service;

import java.util.Map;

/**
 * A small lookup table, initialised once from a static block.
 *
 * <p>The other half of the cycle described on {@link Config}. This class reads
 * {@code Config.ENDPOINT} while building its defaults; {@code Config} calls
 * {@link #lookup(String)} while building its own. Whichever class a thread touches first, it
 * takes that class's initialisation lock and then needs the other one.
 *
 * <p>Note how innocuous each side looks in isolation. Neither file mentions threads, locks or
 * concurrency; the deadlock is a property of the pair plus two threads arriving at the same
 * moment. Single-threaded startup runs it cleanly every time, which is why this reaches
 * production: the first request that arrives concurrently with a warm-up thread hangs the
 * process, and the thread dump shows nothing holding a lock.
 */
public final class Registry {

    private static final Map<String, String> DEFAULTS = Map.of(
            "endpoint", "https://api.example.test",
            "region", "eu-north-1");

    /** BUG: reads back into Config from Registry's own static initialiser. */
    public static final String DESCRIPTION = "registry for " + Config.ENDPOINT;

    private Registry() {
    }

    public static String lookup(String key) {
        return DEFAULTS.getOrDefault(key, "");
    }

    /**
     * The values themselves never needed the cycle. Reading straight from the map keeps this
     * class initialisable on its own, which is the shape to aim for: a static initialiser that
     * calls into no class that can call back.
     */
    public static String lookupWithoutCycle(String key) {
        return DEFAULTS.getOrDefault(key, "");
    }
}
