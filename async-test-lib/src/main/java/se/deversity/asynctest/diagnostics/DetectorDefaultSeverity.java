package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.DetectorType;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AIPublicAPI;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.report.Violation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The severity a detector's findings carry when its own report does not say.
 *
 * <p><strong>Why this exists.</strong> {@link IssueSeverity#fromReport(String)} recovers a
 * finding's severity by matching markers in the report text, and returned {@link
 * IssueSeverity#HIGH} when it found none. 86 of the 142 built-in detectors write no marker, so a
 * merge gate on {@code failOn = HIGH} failed on all of them alike: a spin loop that should yield,
 * an explicit {@code System.gc()} and a lost update were ranked identically, because none of the
 * three said anything and the default said HIGH. That made {@code failOn = HIGH} close to "fail on
 * anything", which is the same as no gate at all.
 *
 * <p>From 1.9.7 every one of those detectors stated its severity here, chosen against
 * {@link IssueSeverity}'s own definitions: {@code CRITICAL} where the report's primary claim is
 * that something will not make progress, {@code HIGH} where it claims corruption or an incorrect
 * result, {@code MEDIUM} for degradation and leaks, {@code LOW} for an inefficiency. Where two
 * readings were defensible the higher was taken, because under-ranking a real bug costs more than
 * over-ranking a benign one.
 *
 * <p><strong>A detector's own report always wins.</strong> This table is consulted only when the
 * report carries no structured severity (see {@link #structuredIn}) and its text marks none, so a
 * detector that learns to state one per finding overrides it
 * without touching this file, and its entry then has to be removed:
 * {@code DetectorSeverityMarkerTest} fails on an entry for a detector that marks its own reports,
 * so the table can only shrink as the detectors improve.
 *
 * <p><strong>The table is empty.</strong> Since #801 every built-in detector keeps its findings as
 * {@link Violation}s at the severity its text used to resolve to, so none falls back to an entry
 * here; the structured severities are the ones this table, or a marker, gave them. {@link
 * #of(DetectorType)} stays for callers and yields empty for every type; a detector added without
 * a structured severity of its own still fails {@code DetectorSeverityMarkerTest} unless it marks
 * its text or regains an entry.
 *
 * <p>Third-party detectors arriving through the SPI are not in this table and keep the historical
 * {@code HIGH} default. The library has no basis for ranking somebody else's finding.
 *
 * @since 1.9.7
 */
@AIPublicAPI
@AIKeepInSync(
    mirrors = {"se.deversity.asynctest.DetectorType", "se.deversity.asynctest.diagnostics.DetectorTrust"},
    reason = "An entry here is the severity a built-in detector's findings carry at the failOn gate "
           + "when its report marks none. A detector missing from both this table and the marker "
           + "convention silently falls back to HIGH, which is the defect this table exists to fix.",
    enforcedBy = "se.deversity.asynctest.architecture.DetectorSeverityMarkerTest"
)
@API(status = Status.EXPERIMENTAL)
public final class DetectorDefaultSeverity {

    /** Empty since #801: every built-in detector now states each finding's severity itself. */
    private static final Map<DetectorType, IssueSeverity> DECLARED = Map.of();

    private DetectorDefaultSeverity() { }

    /**
     * {@return the declared severity for a built-in detector that marks none itself, if any}
     *
     * @param type the detector; {@code null} yields empty
     */
    public static Optional<IssueSeverity> of(DetectorType type) {
        return type == null ? Optional.empty() : Optional.ofNullable(DECLARED.get(type));
    }

    /**
     * {@return the severity a finding should be gated on, from its text alone}
     *
     * <p>Precedence: what the report text marks, then what the detector declares here, then
     * {@link IssueSeverity#HIGH} for anything this library does not know, which is every
     * third-party detector. Callers that hold the report object use
     * {@link #of(String, String, IssueSeverity)} with {@link #structuredIn}, which the text cannot
     * override.
     *
     * @param detectorName the reporting detector's name, as it appears in the report map
     * @param report       the report text
     */
    public static IssueSeverity of(String detectorName, String report) {
        return IssueSeverity.markedIn(report)
                .or(() -> DetectorTrust.typeOfDetector(detectorName).flatMap(DetectorDefaultSeverity::of))
                .orElse(IssueSeverity.HIGH);
    }

    /**
     * {@return the severity a finding should be gated on}
     *
     * <p>The one place that answer is computed, so the {@code failOn} gate, the JSON report and
     * the SARIF output cannot disagree about what a finding was worth. Precedence: the severity
     * the detector put in its structured findings, then {@link #of(String, String)}.
     *
     * <p>The structured severity comes first because it is the one the detector chose per finding.
     * Text matching only guesses: {@code ThreadLocalCacheDegradationDetector} rates every finding
     * {@code MEDIUM} in its {@link Violation}, writes no marker, and has no entry here, so the text
     * route alone reached the {@code HIGH} fallback and failed a {@code failOn = HIGH} build.
     *
     * @param detectorName the reporting detector's name, as it appears in the report map
     * @param report       the report text
     * @param structured   the most severe structured severity of this report, or {@code null}
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public static IssueSeverity of(String detectorName, String report, @Nullable IssueSeverity structured) {
        return structured != null ? structured : of(detectorName, report);
    }

    /**
     * {@return the most severe severity among a report's structured findings, when it keeps any}
     *
     * <p>Built-in reports that hold their findings as {@link Violation}s beside the text expose
     * them in a public {@code List<Violation> structuredViolations} field. There is no shared
     * interface for it, so the field is read by name, the way the SPI bridge read
     * {@code analyze()} until 1.13.0. A report without the field, or with an empty list, yields empty and the
     * caller falls back to the text. {@code DetectorSeverityMarkerTest} relies on the same field
     * name to decide which detectors state their own severity.
     *
     * @param report a detector's report object; {@code null} yields empty
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public static Optional<IssueSeverity> structuredIn(@Nullable Object report) {
        IssueSeverity worst = null;
        for (Violation v : structuredFindingsIn(report)) {
            if (worst == null || v.severity().compareTo(worst) < 0) {
                worst = v.severity();
            }
        }
        return Optional.ofNullable(worst);
    }

    /**
     * {@return a report's structured findings, in its order; empty when it keeps none}
     *
     * <p>Read from the same {@code structuredViolations} field as {@link #structuredIn}. The
     * runner's one-line summary of a folded block counts and heads with these, because a report's
     * text also lists context and advice as bullets, and counting those read advice as a finding
     * (#773).
     *
     * @param report a detector's report object; {@code null} yields empty
     * @since 1.12.4
     */
    @API(status = Status.EXPERIMENTAL)
    public static List<Violation> structuredFindingsIn(@Nullable Object report) {
        if (report == null) {
            return List.of();
        }
        try {
            Field field = report.getClass().getField(STRUCTURED_FIELD);
            if (!List.class.isAssignableFrom(field.getType())
                    || !(field.canAccess(report) || field.trySetAccessible())) {
                return List.of();
            }
            List<Violation> found = new ArrayList<>();
            if (field.get(report) instanceof List<?> findings) {
                for (Object finding : findings) {
                    if (finding instanceof Violation v) {
                        found.add(v);
                    }
                }
            }
            return List.copyOf(found);
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * The public field built-in reports keep their {@link Violation}s in.
     *
     * @since 1.12.3
     */
    public static final String STRUCTURED_FIELD = "structuredViolations";
}
