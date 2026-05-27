package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton configuration manager using double-checked locking (DCL).
 *
 * <p><strong>Bug:</strong> The {@code instance} field is not declared
 * {@code volatile}. The JVM may reorder the write to {@code instance} before the
 * constructor body completes. A second thread passing the first null-check can
 * observe a non-null but incompletely initialized object.
 *
 * <p><strong>Fix:</strong> Declare {@code private static volatile ConfigManager instance}
 * or use the initialization-on-demand holder idiom.
 */
public class ConfigManager {

    // BUG: missing `volatile` — JVM may publish the reference before construction
    private static ConfigManager instance;

    private final Map<String, String> settings;

    private ConfigManager() {
        settings = new HashMap<>();
        settings.put("timeout", "30");
        settings.put("retries", "3");
        settings.put("debug", "false");
    }

    /** Returns the singleton instance via broken double-checked locking. */
    public static ConfigManager getInstance() {
        if (instance == null) {                // first check — no lock
            synchronized (ConfigManager.class) {
                if (instance == null) {        // second check — with lock
                    instance = new ConfigManager(); // may be reordered by JVM
                }
            }
        }
        return instance;
    }

    /** Returns the value for the given key, or {@code null} if absent. */
    public String get(String key) {
        return settings.get(key);
    }

    /** Resets the singleton for testing purposes. */
    public static void reset() {
        instance = null;
    }
}
