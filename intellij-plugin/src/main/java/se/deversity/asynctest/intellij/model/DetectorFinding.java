package se.deversity.asynctest.intellij.model;

import java.util.Locale;

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
                // Locale.ROOT: in a Turkish locale "high".toUpperCase() is "HIGH" with a
                // dotless I, which matches no enum constant and silently degrades to UNKNOWN.
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }
}
