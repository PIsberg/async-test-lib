package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Application-wide configuration singleton loaded lazily on first access.
 *
 * BUG: The {@code instance} field is NOT declared {@code volatile}.
 * Under the Java Memory Model, the JIT compiler and CPU are free to
 * reorder the write to {@code instance} with the writes that initialise
 * its fields. A second thread can observe a non-null {@code instance}
 * whose internal state ({@code properties}, {@code environment}) is
 * still in an uninitialised or partially-constructed state.
 *
 * This is the classic "broken double-checked locking" pattern that was
 * widely used before Java 5. Without {@code volatile}, the double-checked
 * optimisation is unsafe.
 *
 * FIX: Declare {@code instance} as {@code volatile}, or replace the
 * pattern entirely with the initialization-on-demand holder idiom:
 *
 * <pre>
 * private static class Holder {
 *     static final ConfigurationSingleton INSTANCE = new ConfigurationSingleton();
 * }
 * public static ConfigurationSingleton getInstance() { return Holder.INSTANCE; }
 * </pre>
 */
public class ConfigurationSingleton {

    // BUG: missing volatile — broken DCL
    private static ConfigurationSingleton instance;

    private final Map<String, String> properties;
    private final String environment;

    private ConfigurationSingleton() {
        // Simulates loading configuration from disk or a config service.
        this.environment = System.getProperty("app.env", "production");
        this.properties  = new HashMap<>();
        properties.put("db.pool.size",   "20");
        properties.put("cache.ttl.secs", "300");
        properties.put("feature.xray",   "true");
    }

    /**
     * Broken double-checked locking.
     *
     * Without {@code volatile} on {@code instance}, a CPU or compiler may
     * publish the reference before all constructor writes have become
     * globally visible — so a calling thread may see a non-null reference
     * to an object whose fields are still in their default state.
     */
    public static ConfigurationSingleton getInstance() {
        if (instance == null) {                         // first check (unsynchronized)
            synchronized (ConfigurationSingleton.class) {
                if (instance == null) {                 // second check (synchronized)
                    instance = new ConfigurationSingleton(); // BUG: non-volatile write
                }
            }
        }
        return instance;
    }

    public String get(String key) {
        return properties.getOrDefault(key, "");
    }

    public String getEnvironment() {
        return environment;
    }
}
