package com.example.agentfixture;

import java.util.EnumMap;
import java.util.concurrent.TimeUnit;

/**
 * A class whose state is an {@code EnumMap} held as an {@code EnumMap}, not as a {@code Map}.
 *
 * <p>{@code EnumMap.put} narrows its key to {@code Enum}, so a call compiled against the class
 * carries a descriptor no {@code Map.put} row matches, while {@code get} keeps {@code Map}'s. Before
 * the row for it, a run recorded this bean's reads and none of its writes.
 */
public class EnumKeyedStateBean {

    private final EnumMap<TimeUnit, Integer> entries = new EnumMap<>(TimeUnit.class);

    /** Reads and writes the shared map with no synchronization at all. @param unit the key */
    public void record(TimeUnit unit) {
        Integer previous = entries.get(unit);
        entries.put(unit, previous == null ? 1 : previous + 1);
    }

    /** @return how many distinct keys survived the run */
    public int size() {
        return entries.size();
    }
}
