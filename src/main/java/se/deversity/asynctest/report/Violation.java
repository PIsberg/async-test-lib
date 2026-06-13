package se.deversity.asynctest.report;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.SiteCapture;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Structured representation of a single detector finding.
 *
 * <p>Replaces the historical free-text {@code String} reports for new tooling
 * that needs to programmatically consume violations (CI integration, SARIF
 * output, IDE plugins, dashboards). The legacy {@code toString()} form is still
 * produced by detectors for backward compatibility; pluggable {@link Formatter}s
 * derive their output from this record.
 *
 * @param detector   short detector name, e.g. {@code "SharedMessageDigest"}.
 * @param severity   detector-assigned severity (currently {@link IssueSeverity}).
 * @param message    one-line human description of the issue.
 * @param sites      source frames where the offending access happened (may be empty).
 * @param attributes detector-specific data ({@code threads}, {@code type}, etc.).
 * @param when       UTC timestamp at which the report was produced.
 *
 * @since 1.6.0
 */
@AIPublicAPI
@AIImmutable(note = "Java record — fields are final by language. Collection fields are deep-copied to immutable views in the canonical constructor.")
@API(status = Status.STABLE)
public record Violation(
        String detector,
        IssueSeverity severity,
        String message,
        List<SiteCapture.Site> sites,
        Map<String, Object> attributes,
        Instant when
) {
    public Violation {
        if (detector == null || detector.isBlank())
            throw new IllegalArgumentException("detector must be non-blank");
        if (severity == null) throw new IllegalArgumentException("severity must not be null");
        if (message == null) throw new IllegalArgumentException("message must not be null");
        sites = (sites == null) ? List.of() : List.copyOf(sites);
        attributes = (attributes == null) ? Map.of() : Map.copyOf(attributes);
        when = (when == null) ? Instant.now() : when;
    }
}
