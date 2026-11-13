package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects {@link java.text.DecimalFormat}, {@link java.text.NumberFormat} and
 * {@link java.text.MessageFormat} instances accessed from more than one thread.
 *
 * <p>Neither {@code DecimalFormat} nor its parent {@code NumberFormat} is thread-safe.
 * Unsynchronized concurrent {@code format()} / {@code parse()} calls corrupt internal multiplier and
 * grouping state, producing garbled output or {@link java.text.ParseException}.
 * This is the numeric-formatting equivalent of {@code SimpleDateFormat} misuse. A
 * {@code MessageFormat} is reported here too (#1319): its javadoc states that message formats are
 * not synchronized, and it formats its arguments through number and date formats it owns.
 *
 * <p>Synchronization awareness is partial. An access recorded while the accessing thread holds the
 * instance's own monitor - the {@code synchronized (format)} idiom - counts as guarded, and an
 * instance whose every access was guarded produces no finding. A guard on any other lock counts
 * once the test declares it with {@code AsyncTestContext.holdingLock(...)} or the agent sees it
 * taken. A lock that was never declared is invisible and still fires; treat such a finding as a
 * prompt to verify the synchronization, or to move to a per-thread instance.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.SHARED_DECIMAL_FORMAT)}:
 * <pre>{@code
 * var d = AsyncTestContext.sharedDecimalFormatDetector();
 * d.recordAccess(sharedFormat, "currencyFmt", Thread.currentThread());
 * }</pre>
 *
 * @since 0.9.0
 */
public class SharedDecimalFormatDetector
        extends AbstractInstanceDetector<SharedDecimalFormatDetector.FormatState> {

    static final class FormatState extends SelfGuard.ThreadTrackedInstance {
        final String      name;
        /** What the finding calls the instance's kind. */
        final String      kind;

        FormatState(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    @Override
    FormatState newState(Object instance, String label) {
        return new FormatState(label, instance instanceof java.text.MessageFormat
                ? "MessageFormat" : "DecimalFormat/NumberFormat");
    }

    /**
     * Record an access (format/parse/applyPattern) to a DecimalFormat, NumberFormat or
     * MessageFormat instance.
     *
     * @param format the DecimalFormat, NumberFormat or MessageFormat being accessed (null-safe)
     * @param name   descriptive label for reports
     * @param thread the accessing thread
     */
    public void recordAccess(Object format, String name, Thread thread) {
        if (format == null || thread == null) return;
        stateFor(format, name).noteAccess(format, thread);
    }

    /**
     * {@return report of DecimalFormat/NumberFormat instances accessed from multiple threads}
     */
    public SharedDecimalFormatReport analyze() {
        SharedDecimalFormatReport r = new SharedDecimalFormatReport();
        for (FormatState s : states()) {
            if (s.sharedAndUnguarded()) {
                String finding = String.format(
                        "'%s' accessed from %d threads (%s) — %s is not thread-safe"
                                + SelfGuard.REPORT_NOTE,
                        s.name, s.threadCount(),
                        String.join(", ", s.threadNames()), s.kind);
                r.violations.add(finding);
                r.structuredViolations.add(new Violation("SharedDecimalFormat", IssueSeverity.HIGH,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedDecimalFormatReport {
        final List<String> violations = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED DECIMAL FORMAT / NUMBER FORMAT DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("""
  Why: DecimalFormat and NumberFormat maintain mutable internal state during format/parse operations.
       Concurrent use corrupts that state, producing garbled output, NumberFormatException, or
       silently wrong numeric strings — bugs that vary by thread scheduling.
  Fix:
    - Thread-local: ThreadLocal<DecimalFormat> fmt = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));
    - Per-call: create a new DecimalFormat inside the method (cheap for infrequent use)
    - Simple cases: String.format("%.2f", value) — delegates to thread-safe internals\
""");
            return sb.toString();
        }
    }
}
