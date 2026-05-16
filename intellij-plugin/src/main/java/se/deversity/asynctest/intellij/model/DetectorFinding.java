package se.deversity.asynctest.intellij.model;

/**
 * Represents a single concurrency finding parsed from async-test-report.json.
 */
public final class DetectorFinding {

    public final String detectorName;
    public final Severity severity;
    public final String report;
    public final long timestampMs;

    public DetectorFinding(String detectorName, Severity severity, String report, long timestampMs) {
        this.detectorName = detectorName;
        this.severity = severity;
        this.report = report;
        this.timestampMs = timestampMs;
    }

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN;

        public static Severity parse(String s) {
            if (s == null) return UNKNOWN;
            try {
                return valueOf(s.toUpperCase());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }
}
