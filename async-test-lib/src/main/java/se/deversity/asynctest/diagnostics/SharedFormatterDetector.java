package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link java.util.Formatter}, {@link java.io.PrintWriter}, and
 * {@link java.io.PrintStream} instances shared across multiple threads without
 * external synchronization.
 *
 * <p>These classes are not thread-safe. Unsynchronized concurrent use produces interleaved output,
 * garbled format strings, or internal state corruption. {@link System#out} and
 * {@link System#err} are {@code PrintStream} instances that are commonly shared unknowingly.
 *
 * <p>Synchronization awareness is partial. An access recorded while the accessing thread holds
 * the instance's own monitor - the {@code synchronized (out)} idiom, and what {@code PrintStream}
 * does internally - counts as guarded, and an instance whose every access was guarded produces
 * no finding. A guard on any other lock object is invisible and still fires; treat such a
 * finding as a prompt to verify the synchronization, or to move to a per-thread instance.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.sharedFormatterMonitor();
 * mon.recordAccess(sharedFormatter, "sharedFormatter", Thread.currentThread());
 * }</pre>
 */
public class SharedFormatterDetector {

    private static class FormatterState extends SelfGuard.TrackedInstance {
        final String      name;
        final Set<Long>   accessingThreadIds = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        FormatterState(String name) { this.name = name; }
    }

    private final Map<Integer, FormatterState> formatters = new ConcurrentHashMap<>();

    /**
     * Record an access (format/print/write) to a shared formatter or print stream.
     *
     * @param formatter the formatter or print stream being accessed (null-safe)
     * @param name      descriptive label for reports
     * @param thread    the accessing thread
     */
    public void recordAccess(Object formatter, String name, Thread thread) {
        if (formatter == null || thread == null) return;
        String label = name != null ? name
                : formatter.getClass().getSimpleName() + "@" + System.identityHashCode(formatter);
        FormatterState s = formatters.computeIfAbsent(
            System.identityHashCode(formatter), id -> new FormatterState(label));
        s.noteAccess(formatter);
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
    }

    /**
     * {@return report of formatters accessed from multiple threads}
     */
    public SharedFormatterReport analyze() {
        SharedFormatterReport r = new SharedFormatterReport();
        for (FormatterState s : formatters.values()) {
            if (s.accessingThreadIds.size() > 1 && s.sawUnguardedAccess()) {
                r.violations.add(String.format(
                    "'%s' accessed from %d threads (%s) — not thread-safe; unsynchronized concurrent"
                        + " writes interleave output" + SelfGuard.REPORT_NOTE,
                    s.name, s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames)));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedFormatterReport {
        final List<String> violations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED FORMATTER / PRINT-STREAM DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("""
  Why: java.util.Formatter and PrintStream maintain mutable internal buffers. Concurrent writes
       interleave output, producing garbled lines that mix characters from multiple threads.
  Fix:
    - Use a thread-safe logging framework (SLF4J, java.util.logging) which handles concurrent writes
    - Use a thread-local Formatter: ThreadLocal.withInitial(() -> new Formatter())
    - Synchronize externally on the shared formatter/stream for short, infrequent writes\
""");
            return sb.toString();
        }
    }
}
