package com.example.agentfixture;

import java.util.HashMap;
import java.util.Map;

/**
 * A counter map behind {@code synchronized} methods, the way most code guards a {@code HashMap}
 * (#822).
 *
 * <p>The map's own code runs inside {@code java.util}, where nothing is woven, so the collection
 * hooks are all a detector sees of it, and a {@code synchronized} method takes its monitor from an
 * access flag with no instruction to weave. {@link #recordUnguarded} is the broken twin: the same
 * map, reached with no lock.
 */
public final class SynchronizedMethodCacheBean {

    private final Map<String, Integer> counts = new HashMap<>();

    /** Counts {@code key} under this object's monitor, taken by the method itself. @param key the key */
    public synchronized void record(String key) {
        Integer previous = counts.get(key);
        counts.put(key, previous == null ? 1 : previous + 1);
    }

    /** Counts {@code key} with no lock held, the broken twin. @param key the key */
    public void recordUnguarded(String key) {
        Integer previous = counts.get(key);
        counts.put(key, previous == null ? 1 : previous + 1);
    }
}
