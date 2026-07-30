package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects concurrent mutations to JVM system properties via {@link System#setProperty}
 * or {@link System#clearProperty} during an async test run.
 *
 * <p>System properties are global mutable state backed by a single {@link java.util.Properties}
 * instance. Concurrent writes from multiple test threads — or from production code under
 * test — introduce non-deterministic configuration, race conditions in property-reading
 * code, and test pollution that survives to subsequent test methods.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.systemPropertyMutationDetector();
 * // wrap each System.setProperty call:
 * d.recordSet("myapp.timeout", "5000", Thread.currentThread());
 * System.setProperty("myapp.timeout", "5000");
 * }</pre>
 *
 * @since 0.10.0
 */
public class SystemPropertyMutationDetector {

    private static class MutationEvent {
        final String key;
        final String value;    // null means clearProperty
        final long   threadId;
        final String threadName;
        final String operation; // "set" or "clear"

        MutationEvent(String key, String value, long threadId, String threadName, String operation) {
            this.key        = key;
            this.value      = value;
            this.threadId   = threadId;
            this.threadName = threadName;
            this.operation  = operation;
        }
    }

    private final List<MutationEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Records a {@code System.setProperty(key, value)} call.
     *
     * @param key    the property key (null-safe)
     * @param value  the new value
     * @param thread the calling thread (null-safe)
     */
    public void recordSet(String key, String value, Thread thread) {
        if (key == null || thread == null) return;
        events.add(new MutationEvent(key, value, thread.threadId(), thread.getName(), "set"));
    }

    /**
     * Records a {@code System.clearProperty(key)} call.
     *
     * @param key    the property key (null-safe)
     * @param thread the calling thread (null-safe)
     */
    public void recordClear(String key, Thread thread) {
        if (key == null || thread == null) return;
        events.add(new MutationEvent(key, null, thread.threadId(), thread.getName(), "clear"));
    }

    /** {@return report of concurrent system property mutations} */
    public SystemPropertyMutationReport analyze() {
        SystemPropertyMutationReport r = new SystemPropertyMutationReport();

        // Group events by key
        Map<String, List<MutationEvent>> byKey = new LinkedHashMap<>();
        for (MutationEvent e : events) {
            byKey.computeIfAbsent(e.key, k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<String, List<MutationEvent>> entry : byKey.entrySet()) {
            String key = entry.getKey();
            List<MutationEvent> mutations = entry.getValue();

            // Collect distinct thread IDs
            Set<Long>   threadIds   = new LinkedHashSet<>();
            Set<String> threadNames = new LinkedHashSet<>();
            for (MutationEvent e : mutations) {
                threadIds.add(e.threadId);
                threadNames.add(e.threadName);
            }

            if (threadIds.size() > 1) {
                r.violations.add(String.format(
                        "Property '%s' mutated from %d threads (%s) — "
                                + "concurrent property mutation causes non-deterministic "
                                + "configuration and test pollution",
                        key, threadIds.size(), String.join(", ", threadNames)));
            } else if (!mutations.isEmpty()) {
                // Even single-thread mutation is worth reporting as a hygiene issue
                MutationEvent first = mutations.get(0);
                r.singleThreadMutations.add(String.format(
                        "Property '%s' %s by thread '%s' — "
                                + "verify the property is restored after the test",
                        key, "set".equals(first.operation) ? "set to '" + first.value + "'" : "cleared",
                        first.threadName));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SystemPropertyMutationReport {
        final List<String> violations             = new ArrayList<>();
        final List<String> singleThreadMutations  = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SYSTEM PROPERTY MUTATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            if (!singleThreadMutations.isEmpty()) {
                sb.append("  Warnings (single-thread mutations):\n");
                for (String w : singleThreadMutations) sb.append("    - ").append(w).append("\n");
            }
            sb.append("  Why: System.setProperty() modifies a global JVM-wide map. Concurrent mutations from multiple threads\n" +
                       "       race without synchronization. More critically, tests that mutate system properties and do not\n" +
                       "       restore them contaminate every subsequent test in the same JVM — a notoriously hard-to-diagnose\n" +
                       "       source of flaky tests.\n" +
                       "  Fix: Use try/finally to always restore the original value:\n" +
                       "       String old = System.getProperty(key); try { System.setProperty(key, value); doWork(); }\n" +
                       "       finally { if (old == null) System.clearProperty(key); else System.setProperty(key, old); }\n" +
                       "       For test isolation, prefer mocking the consumer of the property rather than mutating the global state.");
            return sb.toString();
        }
    }
}
