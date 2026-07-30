package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link java.text.DecimalFormat} and {@link java.text.NumberFormat} instances
 * shared across multiple threads without external synchronization.
 *
 * <p>Neither {@code DecimalFormat} nor its parent {@code NumberFormat} is thread-safe.
 * Concurrent {@code format()} / {@code parse()} calls corrupt internal multiplier and
 * grouping state, producing garbled output or {@link java.text.ParseException}.
 * This is the numeric-formatting equivalent of {@code SimpleDateFormat} misuse.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.sharedDecimalFormatDetector();
 * d.recordAccess(sharedFormat, "currencyFmt", Thread.currentThread());
 * }</pre>
 *
 * @since 0.9.0
 */
public class SharedDecimalFormatDetector {

    private static class FormatState {
        final String      name;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        FormatState(String name) { this.name = name; }
    }

    private final Map<Integer, FormatState> formats = new ConcurrentHashMap<>();

    /**
     * Record an access (format/parse/applyPattern) to a DecimalFormat or NumberFormat instance.
     *
     * @param format the DecimalFormat or NumberFormat being accessed (null-safe)
     * @param name   descriptive label for reports
     * @param thread the accessing thread
     */
    public void recordAccess(Object format, String name, Thread thread) {
        if (format == null || thread == null) return;
        String label = name != null ? name
                : format.getClass().getSimpleName() + "@" + System.identityHashCode(format);
        FormatState s = formats.computeIfAbsent(
                System.identityHashCode(format), id -> new FormatState(label));
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
    }

    /** {@return report of DecimalFormat/NumberFormat instances accessed from multiple threads} */
    public SharedDecimalFormatReport analyze() {
        SharedDecimalFormatReport r = new SharedDecimalFormatReport();
        for (FormatState s : formats.values()) {
            if (s.accessingThreadIds.size() > 1) {
                r.violations.add(String.format(
                        "'%s' accessed from %d threads (%s) — DecimalFormat/NumberFormat is not thread-safe",
                        s.name, s.accessingThreadIds.size(),
                        String.join(", ", s.accessingThreadNames)));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedDecimalFormatReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED DECIMAL FORMAT / NUMBER FORMAT DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: DecimalFormat and NumberFormat maintain mutable internal state during format/parse operations.\n" +
                       "       Concurrent use corrupts that state, producing garbled output, NumberFormatException, or\n" +
                       "       silently wrong numeric strings — bugs that vary by thread scheduling.\n" +
                       "  Fix:\n" +
                       "    - Thread-local: ThreadLocal<DecimalFormat> fmt = ThreadLocal.withInitial(() -> new DecimalFormat(\"#,##0.00\"));\n" +
                       "    - Per-call: create a new DecimalFormat inside the method (cheap for infrequent use)\n" +
                       "    - Simple cases: String.format(\"%.2f\", value) — delegates to thread-safe internals");
            return sb.toString();
        }
    }
}
