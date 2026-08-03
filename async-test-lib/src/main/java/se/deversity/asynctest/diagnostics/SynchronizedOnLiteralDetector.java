package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@code synchronized} blocks that lock on interned {@link String} literals or
 * JVM-cached boxed primitives ({@link Integer} / {@link Long} in the range [-128, 127]).
 *
 * <p>JVM string interning and integer caching mean that two unrelated classes synchronizing
 * on the same literal string or the same small integer share a <em>single JVM-wide monitor</em>,
 * causing silent cross-module lock coupling, unexpected contention, and potential deadlock.
 *
 * <p>Affected types:
 * <ul>
 *   <li>{@code String} — compile-time string constants and {@link String#intern()} results</li>
 *   <li>{@code Integer.valueOf(n)} and {@code (Integer) n} where {@code -128 <= n <= 127}</li>
 *   <li>{@code Long.valueOf(n)} and {@code (Long) n} where {@code -128 <= n <= 127}</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * String lock = "shared-lock"; // interned — JVM-wide monitor
 * AsyncTestContext.synchronizedOnLiteralMonitor()
 *     .recordMonitorAcquired(lock, Thread.currentThread(), "MyService.doWork");
 * }</pre>
 */
public class SynchronizedOnLiteralDetector {

    private static class LiteralUsage {
        final String      description;
        final Set<Long>   threadIds    = ConcurrentHashMap.newKeySet();
        final Set<String> contexts     = ConcurrentHashMap.newKeySet();

        LiteralUsage(String description) { this.description = description; }
    }

    private final Map<Integer, LiteralUsage> literals = new ConcurrentHashMap<>();

    /**
     * Record a {@code synchronized(monitor)} acquisition.
     * Only flags {@code monitor} if it is a {@code String}, a cached {@code Integer},
     * or a cached {@code Long}; all other objects are ignored.
     *
     * @param monitor the object being locked (null-safe)
     * @param thread  the thread acquiring the lock (null-safe)
     * @param context source location or method name for reports (may be null)
     */
    public void recordMonitorAcquired(Object monitor, Thread thread, String context) {
        if (monitor == null || thread == null) return;
        String description = describeIfLiteral(monitor);
        if (description == null) return;
        int id = System.identityHashCode(monitor);
        LiteralUsage u = literals.computeIfAbsent(id, i -> new LiteralUsage(description));
        u.threadIds.add(thread.threadId());
        if (context != null) u.contexts.add(context);
    }

    @SuppressFBWarnings("ES_COMPARING_STRINGS_WITH_EQ")
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.UseEqualsToCompareStrings", "ReferenceEquality"}) // intentional: s == s.intern() detects literal/interned strings via identity
    private static @Nullable String describeIfLiteral(Object obj) {
        if (obj instanceof String s) {
            // Intentional reference comparison: s == s.intern() is true only for interned (literal) strings
            if (s == s.intern()) return "String literal \"" + s + "\"";
        } else if (obj instanceof Integer i) {
            int v = i;
            if (v >= -128 && v <= 127) return "Integer.valueOf(" + v + ") [JVM cached]";
        } else if (obj instanceof Long l) {
            long v = l;
            if (v >= -128L && v <= 127L) return "Long.valueOf(" + v + ") [JVM cached]";
        }
        return null;
    }

    /** {@return report of synchronized-on-literal usages} */
    public SynchronizedOnLiteralReport analyze() {
        SynchronizedOnLiteralReport r = new SynchronizedOnLiteralReport();
        for (LiteralUsage u : literals.values()) {
            r.violations.add(String.format(
                "synchronized on %s (acquired from %d thread(s)%s) — "
                + "this monitor may be shared JVM-wide, causing unintended coupling and potential deadlock",
                u.description, u.threadIds.size(),
                u.contexts.isEmpty() ? "" : " in: " + String.join(", ", u.contexts)));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SynchronizedOnLiteralReport {
        final List<String> violations = new ArrayList<>();

        /** {@return whether there are issues} */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SYNCHRONIZED ON LITERAL / CACHED OBJECT DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: use a dedicated private final Object lock = new Object() "
                    + "instead of synchronizing on String literals or boxed primitives");
            return sb.toString();
        }
    }
}
