package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects {@link java.util.Formatter}, {@link java.io.PrintWriter}, and
 * {@link java.io.PrintStream} instances shared across multiple threads without
 * external synchronization.
 *
 * <p>These classes are not thread-safe. Unsynchronized concurrent use produces interleaved output,
 * garbled format strings, or internal state corruption. {@link System#out} and
 * {@link System#err} are {@code PrintStream} instances that are commonly shared unknowingly.
 *
 * <p>Synchronization awareness is partial. An access recorded while the accessing thread holds the
 * instance's own monitor - the {@code synchronized (out)} idiom, and what {@code PrintStream} does
 * internally - counts as guarded, and an instance whose every access was guarded produces no
 * finding. A guard on any other lock counts once the test declares it with
 * {@code AsyncTestContext.holdingLock(...)} or the agent sees it taken. A lock that was never
 * declared is invisible and still fires; treat such a finding as a prompt to verify the
 * synchronization, or to move to a per-thread instance.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.sharedFormatterMonitor();
 * mon.recordAccess(sharedFormatter, "sharedFormatter", Thread.currentThread());
 * }</pre>
 */
public class SharedFormatterDetector extends AbstractInstanceDetector<SharedFormatterDetector.FormatterState> {

    static final class FormatterState extends SelfGuard.ThreadTrackedInstance {
        final String      name;

        FormatterState(String name) { this.name = name; }
    }

    @Override
    FormatterState newState(Object instance, String label) {
        return new FormatterState(label);
    }

    /**
     * Record an access (format/print/write) to a shared formatter or print stream.
     *
     * @param formatter the formatter or print stream being accessed (null-safe)
     * @param name      descriptive label for reports
     * @param thread    the accessing thread
     */
    public void recordAccess(Object formatter, String name, Thread thread) {
        if (formatter == null || thread == null) return;
        stateFor(formatter, name).noteAccess(formatter, thread);
    }

    /**
     * {@return report of formatters accessed from multiple threads}
     */
    public SharedFormatterReport analyze() {
        SharedFormatterReport r = new SharedFormatterReport();
        for (FormatterState s : states()) {
            if (s.sharedAndUnguarded()) {
                String finding = String.format(
                    "'%s' accessed from %d threads (%s) — not thread-safe; unsynchronized concurrent"
                        + " writes interleave output" + SelfGuard.REPORT_NOTE,
                    s.name, s.threadCount(),
                    String.join(", ", s.threadNames()));
                r.violations.add(finding);
                r.structuredViolations.add(new Violation("SharedFormatter", IssueSeverity.HIGH,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedFormatterReport {
        final List<String> violations = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

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
