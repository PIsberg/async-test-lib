package se.deversity.asynctest.report;

/**
 * Immutable snapshot of a single detector finding produced during a test run.
 *
 * <p>Severity is inferred from the report text using the {@link se.deversity.asynctest.diagnostics.IssueSeverity}
 * emoji/keyword markers. Reports that contain no severity marker default to {@code "HIGH"}.
 */
public final class DetectorFinding {

    public final String detectorName;
    public final String severity;
    public final String report;
    public final long timestampMs;

    public DetectorFinding(String detectorName, String report, long timestampMs) {
        this.detectorName = detectorName;
        this.report = report != null ? report : "";
        this.timestampMs = timestampMs;
        this.severity = parseSeverity(this.report);
    }

    private static String parseSeverity(String report) {
        if (report.contains("CRITICAL") || report.contains("🔴")) return "CRITICAL";
        if (report.contains("HIGH")     || report.contains("🟠")) return "HIGH";
        if (report.contains("MEDIUM")   || report.contains("🟡")) return "MEDIUM";
        if (report.contains("LOW")      || report.contains("🟢")) return "LOW";
        return "HIGH";
    }
}
