package com.example.agentfixture;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two lost-update shapes whose only woven instructions are substituted calls: everything arrives as a
 * parameter, so no field access sits between the read and the write. Under a controlled schedule
 * the round can change hands between them only if the substituted calls are scheduling points.
 */
public final class HookOnlyRaces {

    private HookOnlyRaces() {
    }

    /**
     * Puts {@code key} unless it is already there: check then act, with no lock.
     *
     * @return whether this call made the put, so two winners for one key is the lost race
     */
    public static boolean claim(Map<String, Integer> map, String key, int id) {
        if (!map.containsKey(key)) {
            map.put(key, id);
            return true;
        }
        return false;
    }

    /** Reads the count, then stores one more: a lost update when another bump lands between. */
    public static void bump(AtomicInteger count) {
        int seen = count.get();
        count.set(seen + 1);
    }
}
