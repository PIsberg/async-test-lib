package se.deversity.asynctest.report;

import se.deversity.asynctest.diagnostics.IssueSeverity;

/**
 * Immutable snapshot of a single detector finding produced during a test run.
 */
public final class DetectorFinding {

    /** Detector that raised this finding. */
    public final String detectorName;
    /** How the finding is weighed by the {@code failOn} gate. */
    public final IssueSeverity severity;
    /** Human-readable detail shown in the report. */
    public final String report;
    /** The timestamp in milliseconds. */
    public final long timestampMs;
    /**
     * Creates a DetectorFinding.
     *
     * @param detectorName the detector that raised this finding
     * @param severity how the finding is weighed by the {@code failOn} gate
     * @param report the human-readable detail shown in the report
     * @param timestampMs the timestamp in milliseconds
     */
    public DetectorFinding(String detectorName, IssueSeverity severity, String report, long timestampMs) {
        this.detectorName = detectorName;
        this.severity = severity != null ? severity : IssueSeverity.HIGH;
        this.report = report != null ? report : "";
        this.timestampMs = timestampMs;
    }
}
