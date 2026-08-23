package com.example.agentfixture;

import java.util.HashMap;
import java.util.Map;

/**
 * A class whose mutable state lives entirely inside a JDK collection.
 *
 * <p>The shape the corpus eval found invisible: {@code entries} is final and never reassigned, so
 * there is no {@code PUTFIELD} to weave, and the write that actually races happens inside
 * {@code java.util.HashMap}, which the agent never instruments. Field weaving sees nothing here no
 * matter how many threads collide, which is why the collection call itself has to be observed.
 */
public class DelegatedStateBean {

    private final Map<String, Integer> entries = new HashMap<>();

    /** Reads and writes the shared map with no synchronization at all. @param key the key */
    public void record(String key) {
        Integer previous = entries.get(key);
        entries.put(key, previous == null ? 1 : previous + 1);
    }

    /** @return how many distinct keys survived the run */
    public int size() {
        return entries.size();
    }
}
