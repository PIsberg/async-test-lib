package se.deversity.asynctest.report;

import se.deversity.asynctest.diagnostics.IssueSeverity;

/**
 * Immutable snapshot of a single detector finding produced during a test run.
 */
public final class DetectorFinding {

    public final String detectorName;
    public final IssueSeverity severity;
    public final String report;
    public final long timestampMs;

    public DetectorFinding(String detectorName, IssueSeverity severity, String report, long timestampMs) {
        this.detectorName = detectorName;
        this.severity = severity != null ? severity : IssueSeverity.HIGH;
        this.report = report != null ? report : "";
        this.timestampMs = timestampMs;
    }
}
