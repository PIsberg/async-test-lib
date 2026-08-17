package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A session lookup, written twice: once behind a monitor and once behind a concurrent map.
 *
 * <p>Both are correct. The difference is how many threads can be inside at the same time, which
 * did not matter when a pool of eight was the most that could ever arrive.
 */
public final class SessionCache {

    /** The synchronized variant's store. Correct, and one thread at a time. */
    private final Map<String, String> guarded = new HashMap<>();

    /** The concurrent variant's store. Correct, and as many threads as the hardware allows. */
    private final Map<String, String> concurrent = new ConcurrentHashMap<>();

    /** The monitor the guarded variant serialises on. */
    private final Object lock = new Object();

    /** {@return the monitor callers must record around} */
    public Object lock() {
        return lock;
    }

    /**
     * The buggy shape's critical section: the whole lookup, including the expensive part, inside
     * the monitor.
     *
     * <p>Callers must hold {@link #lock()}.
     *
     * @param key the session id
     * @return the session payload
     */
    public String lookupGuarded(String key) {
        String cached = guarded.get(key);
        if (cached != null) {
            return cached;
        }
        String loaded = load(key);      // the expensive part, done while everyone else waits
        guarded.put(key, loaded);
        return loaded;
    }

    /**
     * The fixed shape: no monitor at all, so arrivals do not queue.
     *
     * @param key the session id
     * @return the session payload
     */
    public String lookupConcurrent(String key) {
        return concurrent.computeIfAbsent(key, SessionCache::load);
    }

    /** Stands in for the work that makes the critical section worth shortening. */
    private static String load(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            sb.append(key.hashCode() + i);
        }
        return sb.substring(0, Math.min(16, sb.length()));
    }
}
