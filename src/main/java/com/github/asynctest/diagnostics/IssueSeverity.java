package com.github.asynctest.diagnostics;

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
     * @return the display label with emoji indicator
     */
    public String getLabel() {
        return label;
    }

    /**
     * @return a brief description of what this severity means
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return ANSI color code for terminal output
     */
    public String getAnsiColor() {
        switch (this) {
            case CRITICAL: return "\u001B[31m"; // Red
            case HIGH:     return "\u001B[33m"; // Yellow
            case MEDIUM:   return "\u001B[36m"; // Cyan
            case LOW:      return "\u001B[34m"; // Blue
            default:       return "\u001B[0m";  // Reset
        }
    }

    /**
     * @return ANSI reset code
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
}
