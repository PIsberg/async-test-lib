package com.example.agentfixture;

import java.util.TreeMap;
import java.util.Map;

/**
 * The correctly synchronized twin of {@link DelegatedStateBean}.
 *
 * <p>Deliberately a {@code TreeMap} where the racing twin holds a {@code HashMap}: findings are
 * labelled by collection type, so two different types are what let one test assert that one
 * subject was reported and the other was not. Both are documented as unsynchronized, so the
 * property under test is unchanged.
 *
 * <p>Guards with its own monitor rather than the map's, and never declares the lock to the library,
 * which is the ordinary way real code is written. The agent weaves the {@code MONITORENTER} the
 * {@code synchronized} block compiles to, so the lockset sees a lock held across every access and
 * the detector has nothing to report. A finding here would be a false positive on correct code.
 */
public class GuardedDelegatedStateBean {

    private final Map<String, Integer> entries = new TreeMap<>();

    /** Reads and writes the shared map under this object's monitor. @param key the key */
    public void record(String key) {
        synchronized (this) {
            Integer previous = entries.get(key);
            entries.put(key, previous == null ? 1 : previous + 1);
        }
    }

    /** @return how many distinct keys survived the run */
    public int size() {
        synchronized (this) {
            return entries.size();
        }
    }
}
