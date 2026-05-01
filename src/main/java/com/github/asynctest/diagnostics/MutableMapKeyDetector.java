package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Detects mutable objects used as {@link java.util.HashMap} / {@link java.util.HashSet} keys
 * that are mutated after insertion.
 *
 * <p>The Java {@code Map} / {@code Set} contract requires that a key's {@code equals()} and
 * {@code hashCode()} values remain stable for as long as the key is in the collection.
 * Mutating a key after insertion silently breaks lookups and remove operations because the
 * key is now stored in the wrong hash bucket.
 *
 * <p>This is particularly insidious in concurrent code where one thread holds the key
 * reference and mutates it while another thread performs map reads.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectMutableMapKeys = true)
 * void testMapKeyMutation() {
 *     MutableKey key = new MutableKey("initial");
 *     map.put(key, "value");
 *     AsyncTestContext.mutableMapKeyMonitor()
 *         .recordKeyInserted(map, key, "my-map");
 *
 *     key.setName("mutated");  // BUG: key mutated after insertion
 *     AsyncTestContext.mutableMapKeyMonitor()
 *         .recordKeyMutation(key, "name", "initial", "mutated");
 * }
 * }</pre>
 */
public class MutableMapKeyDetector {

    private static class KeyRegistration {
        final String mapName;
        final int originalHashCode;
        final String keyDescription;
        final AtomicInteger mutationCount = new AtomicInteger(0);
        volatile int hashCodeAtLastMutation = -1;

        KeyRegistration(String mapName, int originalHashCode, String keyDescription) {
            this.mapName        = mapName;
            this.originalHashCode = originalHashCode;
            this.keyDescription = keyDescription;
        }
    }

    private final Map<Integer, KeyRegistration> registeredKeys = new ConcurrentHashMap<>();
    private final List<String> mutationDetails = new CopyOnWriteArrayList<>();

    /**
     * Record that {@code key} was inserted into {@code map} as a key.
     * Call this immediately after {@code map.put(key, value)}.
     *
     * @param map     the map the key was inserted into (null-safe)
     * @param key     the key object (null-safe)
     * @param mapName descriptive name for the map in reports
     */
    public void recordKeyInserted(Map<?, ?> map, Object key, String mapName) {
        if (map == null || key == null) return;
        String resolved = mapName != null ? mapName : "map@" + System.identityHashCode(map);
        String desc = key.getClass().getSimpleName() + "@" + System.identityHashCode(key);
        registeredKeys.put(System.identityHashCode(key),
                new KeyRegistration(resolved, key.hashCode(), desc));
    }

    /**
     * Record a field mutation on an object that was previously registered as a map key.
     * Call this immediately after updating the field on the key object.
     *
     * @param key       the key object being mutated (null-safe)
     * @param fieldName name of the mutated field (for reporting)
     * @param oldValue  value before mutation
     * @param newValue  value after mutation
     */
    public void recordKeyMutation(Object key, String fieldName, Object oldValue, Object newValue) {
        if (key == null) return;
        KeyRegistration reg = registeredKeys.get(System.identityHashCode(key));
        if (reg == null) return;

        reg.mutationCount.incrementAndGet();
        int currentHash = key.hashCode();
        if (currentHash != reg.originalHashCode) {
            reg.hashCodeAtLastMutation = currentHash;
            mutationDetails.add(String.format(
                "%s in map '%s': field '%s' changed %s→%s; hashCode changed %d→%d — key is now in wrong bucket",
                reg.keyDescription, reg.mapName, fieldName, oldValue, newValue,
                reg.originalHashCode, currentHash));
        } else {
            mutationDetails.add(String.format(
                "%s in map '%s': field '%s' changed %s→%s (hashCode stable but equals contract may be broken)",
                reg.keyDescription, reg.mapName, fieldName, oldValue, newValue));
        }
    }

    /**
     * Analyze registered keys for post-insertion mutations.
     *
     * @return report describing mutated keys
     */
    public MutableMapKeyReport analyze() {
        MutableMapKeyReport report = new MutableMapKeyReport();
        for (KeyRegistration reg : registeredKeys.values()) {
            if (reg.mutationCount.get() > 0) {
                report.mutatedKeys.add(String.format(
                    "%s in map '%s': mutated %d time(s) after insertion",
                    reg.keyDescription, reg.mapName, reg.mutationCount.get()));
            }
        }
        report.mutationDetails.addAll(mutationDetails);
        return report;
    }

    /** Report produced by {@link #analyze()}. */
    public static class MutableMapKeyReport {
        final List<String> mutatedKeys    = new ArrayList<>();
        final List<String> mutationDetails = new ArrayList<>();

        public boolean hasIssues() {
            return !mutatedKeys.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MUTABLE MAP KEY ISSUES DETECTED:\n");
            for (String issue : mutatedKeys) sb.append("  - ").append(issue).append("\n");
            if (!mutationDetails.isEmpty()) {
                sb.append("  Mutation details:\n");
                for (String detail : mutationDetails) sb.append("    * ").append(detail).append("\n");
            }
            sb.append("  Fix: use only immutable objects as Map/Set keys, "
                    + "or restrict hashCode/equals to final fields");
            return sb.toString();
        }
    }
}
