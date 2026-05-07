package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;

/**
 * Detects {@link java.util.TimeZone} instances whose mutable state is modified while
 * being accessed from multiple threads.
 *
 * <p>{@code TimeZone} is a mutable class: {@code setRawOffset()} and {@code setID()}
 * alter the shared instance in place. Although read operations are effectively safe
 * in practice, concurrent writes (or a write concurrent with a read) produce
 * non-deterministic timezone offsets and IDs — silently wrong date/time arithmetic
 * that is notoriously hard to reproduce.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.sharedTimeZoneDetector();
 * TimeZone tz = TimeZone.getDefault();
 * // before mutating:
 * d.recordMutation(tz, "setRawOffset", Thread.currentThread());
 * tz.setRawOffset(3600_000);
 * }</pre>
 *
 * @since 0.10.0
 */
public class SharedTimeZoneDetector {

    private static class TzState {
        final Set<Long>   mutatingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> mutatingThreadNames = ConcurrentHashMap.newKeySet();
        volatile String   firstOperation;
    }

    private final Map<Integer, TzState> timezones = new ConcurrentHashMap<>();

    /**
     * Records a mutating operation on a {@code TimeZone} instance.
     *
     * @param timeZone  the {@code TimeZone} being mutated (null-safe)
     * @param operation the method called, e.g. {@code "setRawOffset"} or {@code "setID"}
     * @param thread    the mutating thread (null-safe)
     */
    public void recordMutation(Object timeZone, String operation, Thread thread) {
        if (timeZone == null || thread == null) return;
        TzState s = timezones.computeIfAbsent(System.identityHashCode(timeZone),
                id -> new TzState());
        if (s.firstOperation == null) s.firstOperation = operation != null ? operation : "mutate";
        s.mutatingThreadIds.add(thread.getId());
        s.mutatingThreadNames.add(thread.getName());
    }

    /** @return report of TimeZone instances mutated from multiple threads */
    public SharedTimeZoneReport analyze() {
        SharedTimeZoneReport r = new SharedTimeZoneReport();
        for (TzState s : timezones.values()) {
            if (s.mutatingThreadIds.size() > 1) {
                r.violations.add(String.format(
                        "TimeZone instance mutated from %d threads (%s) via '%s' — "
                                + "concurrent mutations corrupt date/time arithmetic silently",
                        s.mutatingThreadIds.size(),
                        String.join(", ", s.mutatingThreadNames),
                        s.firstOperation));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedTimeZoneReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED TIMEZONE MUTATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: treat TimeZone instances as immutable after construction — "
                    + "use ZoneId (java.time) which is immutable and thread-safe, or obtain "
                    + "a fresh TimeZone.getTimeZone(id) copy per thread if mutation is required");
            return sb.toString();
        }
    }
}
