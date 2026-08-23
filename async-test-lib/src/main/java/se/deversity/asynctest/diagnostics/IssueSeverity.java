package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Severity levels for concurrency issues detected by async-test.
 *
 * <p>Severity helps prioritize which issues to fix first:
 * <ul>
 *   <li><strong>CRITICAL</strong> - Application will hang or crash; must fix immediately
 *   <li><strong>HIGH</strong> - Data corruption or incorrect results possible; fix before production
 *   <li><strong>MEDIUM</strong> - Performance degradation or resource leaks; fix soon
 *   <li><strong>LOW</strong> - Minor inefficiencies or best practice violations; fix when convenient
 * </ul>
 *
 * @since 1.3.0
 */
@API(status = Status.STABLE)
public enum IssueSeverity {

    /**
     * 🔴 Application will hang, deadlock, or crash.
     * Requires immediate attention.
     */
    CRITICAL("🔴 CRITICAL", "Application will hang, deadlock, or crash"),

    /**
     * 🟠 Data corruption, incorrect results, or lost updates possible.
     * Must fix before deploying to production.
     */
    HIGH("🟠 HIGH", "Data corruption or incorrect results possible"),

    /**
     * 🟡 Performance degradation, resource leaks, or thread starvation.
     * Should fix in the near term.
     */
    MEDIUM("🟡 MEDIUM", "Performance degradation or resource leaks"),

    /**
     * 🟢 Minor inefficiencies, best practice violations, or potential future issues.
     * Fix when convenient or during code review.
     */
    LOW("🟢 LOW", "Minor inefficiencies or best practice violations");

    private final String label;
    private final String description;

    IssueSeverity(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /**
     * {@return the display label with emoji indicator}
     */
    public String getLabel() {
        return label;
    }

    /**
     * {@return a brief description of what this severity means}
     */
    public String getDescription() {
        return description;
    }

    /**
     * {@return ANSI color code for terminal output}
     */
    public String getAnsiColor() {
        return switch (this) {
            case CRITICAL -> "\033[31m"; // Red
            case HIGH     -> "\033[33m"; // Yellow
            case MEDIUM   -> "\033[36m"; // Cyan
            case LOW      -> "\033[34m"; // Blue
        };
    }

    /**
     * {@return ANSI reset code}
     */
    public String getAnsiReset() {
        return "\u001B[0m";
    }

    /**
     * Format this severity for terminal output with colors.
     *
     * @return colored severity label
     */
    public String format() {
        return getAnsiColor() + label + getAnsiReset();
    }

    /**
     * Bare-word fallback markers: the severity name as a standalone, upper-case word
     * (e.g. matches "... CRITICAL ..." or "[CRITICAL]" but not "critical section" —
     * wrong case — and not "ALLOWANCE" or "BELOW" — no word boundary around the
     * embedded "LOW"). Case-sensitive and word-bounded on purpose: every real marker
     * this codebase's detectors render ({@link #format()} / {@link #getLabel()}) is
     * upper-case, while ordinary explanatory prose in report text (fix suggestions,
     * learning content) uses natural mixed case, so restricting to upper-case avoids
     * misreading that prose as a severity marker.
     */
    private static final Pattern CRITICAL_WORD = wordBoundary("CRITICAL");
    private static final Pattern HIGH_WORD     = wordBoundary("HIGH");
    private static final Pattern MEDIUM_WORD   = wordBoundary("MEDIUM");
    private static final Pattern LOW_WORD      = wordBoundary("LOW");

    private static Pattern wordBoundary(String upperCaseWord) {
        return Pattern.compile("\\b" + upperCaseWord + "\\b");
    }

    /**
     * Infers the severity from a detector report string.
     *
     * <p>Matching is tiered, most specific first, so incidental mentions of severity
     * words in explanatory prose (e.g. a fix suggestion that says "reduce the critical
     * section" or "this allows...") never get mistaken for a real marker:
     * <ol>
     *   <li>The exact label this codebase's detectors render via {@link #format()} /
     *       {@link #getLabel()} (e.g. {@code "🔴 CRITICAL"}), or the common
     *       {@code "[CRITICAL]"} / {@code "Severity: CRITICAL"} conventions used by
     *       external or future detectors — checked worst-to-best so a report that
     *       quotes more than one marker resolves to the worst.</li>
     *   <li>The severity emoji alone (🔴/🟠/🟡/🟢), in case a marker was reformatted
     *       without its text label.</li>
     *   <li>A bare, upper-case, word-bounded severity token (see {@link #CRITICAL_WORD}
     *       et al.) for detectors that emit plain text without the label/emoji
     *       convention above.</li>
     * </ol>
     * Defaults to {@link #HIGH} when no marker is present, matching the assumption
     * that untagged reports are significant.
     *
     * @param report the raw report text produced by a detector; {@code null} is treated as empty
     * @return the matched severity, or {@link #HIGH} if none is found
     */
    public static IssueSeverity fromReport(String report) {
        return markedIn(report).orElse(HIGH);
    }

    /**
     * {@return the severity this report explicitly marks, or empty when it marks none}
     *
     * <p>Same matching as {@link #fromReport(String)}, without the {@code HIGH} default. The
     * distinction is the point: a caller can tell "this detector said HIGH" from "this detector
     * said nothing and HIGH is what we assumed", and 86 of the 142 built-in detectors said
     * nothing. {@code DetectorDefaultSeverity} supplies the answer for those instead of letting
     * every one of them arrive at a merge gate ranked as though it proved data corruption.
     *
     * @param report the raw report text produced by a detector; {@code null} is treated as empty
     * @since 1.9.7
     */
    public static Optional<IssueSeverity> markedIn(String report) {
        return Optional.ofNullable(marked(report));
    }

    private static @Nullable IssueSeverity marked(String report) {
        if (report == null || report.isEmpty()) return null;

        for (IssueSeverity severity : values()) {
            if (report.contains(severity.getLabel())
                    || report.contains("[" + severity.name() + "]")
                    || report.contains("Severity: " + severity.name())) {
                return severity;
            }
        }

        if (report.contains("🔴")) return CRITICAL;
        if (report.contains("🟠")) return HIGH;
        if (report.contains("🟡")) return MEDIUM;
        if (report.contains("🟢")) return LOW;

        if (CRITICAL_WORD.matcher(report).find()) return CRITICAL;
        if (HIGH_WORD.matcher(report).find())     return HIGH;
        if (MEDIUM_WORD.matcher(report).find())   return MEDIUM;
        if (LOW_WORD.matcher(report).find())      return LOW;

        return null;
    }
}
