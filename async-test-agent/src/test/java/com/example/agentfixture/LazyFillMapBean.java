package com.example.agentfixture;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fills a key of a {@link ConcurrentHashMap} if it is absent, the way a lazy cache is usually
 * written: {@code containsKey}, then {@code put}.
 *
 * <p>Each call is atomic and the pair is not, so two callers that both find the key absent both
 * put, and one of their values is dropped. {@link #fillUnderTheMapsMonitor} is the same pair
 * inside {@code synchronized (map)}, which makes it atomic. Woven with {@code collections=true},
 * the two calls reach the hooks with nothing recorded by hand.
 */
public class LazyFillMapBean {

    private final ConcurrentMap<String, Object> cache = new ConcurrentHashMap<>();

    /**
     * Checks {@code key}, runs {@code afterCheck}, and puts a new object if the check found the key
     * absent.
     *
     * @param key        the key to fill
     * @param afterCheck run between the check and the put, which is where a racing caller lands
     */
    public void fill(String key, Runnable afterCheck) {
        boolean present = cache.containsKey(key);
        afterCheck.run();
        if (!present) {
            cache.put(key, new Object());
        }
    }

    /**
     * The same check and put, both under the map's own monitor.
     *
     * @param key the key to fill
     */
    public void fillUnderTheMapsMonitor(String key) {
        synchronized (cache) {
            if (!cache.containsKey(key)) {
                cache.put(key, new Object());
            }
        }
    }
}
