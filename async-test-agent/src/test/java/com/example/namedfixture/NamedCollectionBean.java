package com.example.namedfixture;

import java.util.HashMap;
import java.util.Map;

/**
 * A class keeping its state in a JDK collection, which the test defines in a named module of its
 * own and weaves with {@code collections=true} alone (#862).
 *
 * <p>A named module reads nothing it does not require, so the substituted collection calls and the
 * monitor hooks can link to a library on the class path only if the agent adds that read edge.
 * Nothing in the test refers to this class by type: a reference would load a second copy in the
 * test's own unnamed module.
 */
public final class NamedCollectionBean {

    private final Map<String, Integer> counts = new HashMap<>();

    /** {@return the new count for {@code key}}, through a substituted put and get under a monitor */
    public int record(String key) {
        synchronized (this) {
            counts.put(key, counts.getOrDefault(key, 0) + 1);
            return counts.get(key);
        }
    }

    /** {@return how many keys were recorded}, an accessor the default mode's Advice weaves */
    public int getSize() {
        synchronized (this) {
            return counts.size();
        }
    }
}
