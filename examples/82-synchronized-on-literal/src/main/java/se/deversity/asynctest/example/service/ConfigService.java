package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;

/**
 * BUGGY service that demonstrates synchronizing on an interned String literal.
 *
 * BUG: set() and get() use synchronized("config-lock"). The JVM interns String
 *      literals — "config-lock" is always the same object across the entire JVM
 *      class loader hierarchy. Any other class that also uses synchronized("config-lock")
 *      shares this lock accidentally, causing unrelated code to block on
 *      ConfigService operations and vice versa.
 *
 * FIX: replace the String literal with a private final Object lock:
 *
 * <pre>{@code
 * private final Object lock = new Object();
 * // then: synchronized (lock) { ... }
 * }</pre>
 */
public class ConfigService {

    private final Map<String, String> config = new HashMap<>();

    /**
     * Store a configuration value.
     * BUG: synchronizes on the JVM-global interned String "config-lock".
     */
    public void set(String key, String value) {
        synchronized ("config-lock") {  // BUG: interned String literal as monitor
            config.put(key, value);
        }
    }

    /**
     * Retrieve a configuration value.
     * BUG: synchronizes on the same JVM-global interned String literal.
     */
    public String get(String key) {
        synchronized ("config-lock") {  // BUG: interned String literal as monitor
            return config.get(key);
        }
    }

    public int size() {
        synchronized ("config-lock") {
            return config.size();
        }
    }
}
