package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.DetectorDefaultSeverity;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ratchet on detectors that leave their severity to be guessed.
 *
 * <p><strong>Why this exists.</strong> {@link IssueSeverity#fromReport(String)} is how the
 * {@code failOn} gate learns a finding's severity on the main reporting path. It looks for a
 * label, a bracketed token, a coloured emoji or a word-bounded upper-case severity word in the
 * report text, and <strong>returns {@code HIGH} when it finds none</strong>. A detector that never
 * writes a marker therefore arrives at a merge gate ranked at the second-highest severity the
 * library has, whatever it actually meant.
 *
 * <p>That is issue #291, and it is not a defect this gate fixes: choosing the right severity for
 * each of the detectors below is a per-detector judgement that changes what existing builds fail
 * on, which is an owner's call and a MINOR release. What this gate does is stop the number growing.
 * A new detector that writes no marker fails here, and every detector that gains one lets the
 * baseline drop.
 *
 * <p><strong>This is a heuristic over source text, and says so.</strong> It cannot see a severity
 * set through a helper or a base class, so a detector counted as unmarked may in fact set one.
 * That direction is safe: it makes the baseline larger than the true figure, and the ratchet still
 * only ever moves down. The authoritative measurement would drive every detector to a report and
 * check what {@code fromReport} returns, which needs the fixture coverage tracked in #293.
 */
class DetectorSeverityMarkerTest {

    /** The ways this codebase's detectors render a severity that {@code fromReport} recognises. */
    private static final List<String> MARKERS = List.of(
            "IssueSeverity.", "getLabel()", "[CRITICAL]", "[HIGH]", "[MEDIUM]", "[LOW]",
            "Severity: ", "🔴", "🟠", "🟡", "🟢");

    @Test
    @DisplayName("every detector states its severity, by marker or by declaration")
    void noDetectorFallsThroughToTheDefault() {
        List<String> silent = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (!hasMarker(row.detectorClass()) && DetectorDefaultSeverity.of(row.type()).isEmpty()) {
                silent.add(row.detectorClass());
            }
        }
        assertTrue(silent.isEmpty(),
                "A detector that neither marks its report nor declares a default has its severity "
                        + "guessed by IssueSeverity.fromReport, which returns HIGH, so it reaches a "
                        + "failOn = HIGH merge gate as though it proved data corruption. That was "
                        + "true of 86 detectors and is the defect DetectorDefaultSeverity closed. "
                        + "Either open the report with IssueSeverity.<LEVEL>.getLabel() or add an "
                        + "entry to DetectorDefaultSeverity. Silent: " + silent);
    }

    @Test
    @DisplayName("the declaration table only covers detectors that say nothing themselves")
    void declaredDefaultsDoNotShadowADetectorsOwnSeverity() {
        List<String> redundant = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (hasMarker(row.detectorClass()) && DetectorDefaultSeverity.of(row.type()).isPresent()) {
                redundant.add(row.detectorClass());
            }
        }
        assertTrue(redundant.isEmpty(),
                "A detector's own report wins over the table, so an entry for a detector that marks "
                        + "its own severity is dead weight that reads as though it were in force. "
                        + "Remove the entry: the table is meant to shrink as detectors learn to "
                        + "state their own. Redundant: " + redundant);
    }

    @Test
    @DisplayName("an advisory-tier detector cannot declare a correctness severity")
    void advisoryTierDetectorsCannotClaimCorruption() {
        List<String> overclaiming = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (row.tier() != TrustTier.ADVISORY) continue;
            DetectorDefaultSeverity.of(row.type())
                    .filter(severity -> severity == IssueSeverity.CRITICAL || severity == IssueSeverity.HIGH)
                    .ifPresent(severity -> overclaiming.add(row.detectorClass() + " declares " + severity));
        }
        assertTrue(overclaiming.isEmpty(),
                "An ADVISORY-tier detector makes a performance or hygiene note. Declaring it "
                        + "CRITICAL or HIGH would put a finding that says nothing about correctness "
                        + "into the same bucket as a lost update: " + overclaiming);
    }

    private static boolean hasMarker(String detectorClass) {
        Path source = repoRoot().resolve(
                "async-test-lib/src/main/java/se/deversity/asynctest/diagnostics/" + detectorClass + ".java");
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException(
                    "No source for detector class " + detectorClass + " at " + source
                            + ". DetectorTrust names the class that produces the report; if a detector "
                            + "moved package, this gate and DetectorTrustCoverageTest both need to know.");
        }
        String code = stripComments(read(source));
        return MARKERS.stream().anyMatch(code::contains);
    }

    /**
     * Removes block and line comments so a detector cannot look marked because its Javadoc
     * mentions {@code IssueSeverity}.
     *
     * <p>Found the hard way. The first version of this gate scanned the raw source, and deleting
     * the one marker from {@code FalseSharingDetector} left it green, because the comment
     * explaining the marker still contained the word. A gate that a comment can satisfy measures
     * nothing.
     *
     * <p>Deliberately naive: it does not understand a comment delimiter inside a string literal.
     * A detector whose report text contains {@code //} would lose the rest of that line, which can
     * only make this gate see fewer markers, never more, so the ratchet stays honest.
     */
    private static String stripComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            if (source.startsWith("/*", index)) {
                int close = source.indexOf("*/", index + 2);
                index = close < 0 ? source.length() : close + 2;
            } else if (source.startsWith("//", index)) {
                int newline = source.indexOf('\n', index);
                index = newline < 0 ? source.length() : newline;
            } else {
                code.append(source.charAt(index));
                index++;
            }
        }
        return code.toString();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
