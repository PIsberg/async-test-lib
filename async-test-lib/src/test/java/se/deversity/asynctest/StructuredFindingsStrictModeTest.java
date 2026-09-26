package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A report that keeps a {@code structuredViolations} list and has issues must put at least one
 * finding in it, checked where every built-in report enters the run's findings (#802).
 *
 * <p>{@code StructuredViolationCoverageTest} holds each structured detector to one hand-written
 * driver. A finding path added later with no driver, whose report has issues and an empty list,
 * reaches the {@code failOn} gate as text only and is judged by a guess, and that gate stays green.
 * Checking in {@link DetectorRegistry#ifIssue} instead makes every test in this build that makes a
 * detector fire a driver. The check is this build's business, not a consumer's: outside
 * {@value DetectorFailurePolicy#STRICT_PROPERTY} it neither throws nor writes anything, and the
 * finding is reported exactly as before.
 */
@DisplayName("Strict mode: a report with issues and an empty structured list fails the build")
class StructuredFindingsStrictModeTest {

    @AfterEach
    void restoreStrictMode() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
    }

    @Test
    @DisplayName("strict: an empty structured list on a report with issues is an AssertionError naming the detector")
    void strictModeFailsAReportWithIssuesAndNoStructuredFinding() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        FindingSink out = new FindingSink();

        AssertionError raised = assertThrows(AssertionError.class, () ->
                DetectorRegistry.ifIssue(new PartialDetector(), d -> new StructuredReport(List.of()),
                        StructuredReport::hasIssues, out),
                "a structured report with issues and nothing in its list must fail this build");

        assertTrue(raised.getMessage().contains("PartialDetector"),
                "the failure must name the detector: " + raised.getMessage());
        assertTrue(raised.getMessage().contains("structuredViolations"),
                "the failure must name the list that stayed empty: " + raised.getMessage());
    }

    @Test
    @DisplayName("strict: a filled structured list passes and carries its severity")
    void strictModeAcceptsAFilledStructuredList() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        FindingSink out = new FindingSink();
        Violation finding = new Violation("PartialDetector", IssueSeverity.MEDIUM, "one finding",
                List.of(), Map.of(), null);

        assertDoesNotThrow(() ->
                DetectorRegistry.ifIssue(new PartialDetector(), d -> new StructuredReport(List.of(finding)),
                        StructuredReport::hasIssues, out));

        assertEquals(IssueSeverity.MEDIUM, out.severities().get("PartialDetector"),
                "the structured severity reaches the sink: " + out.severities());
    }

    @Test
    @DisplayName("strict: a text-only report type promised no list and is not held to one")
    void strictModeSkipsATextOnlyReportType() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        FindingSink out = new FindingSink();

        assertDoesNotThrow(() ->
                DetectorRegistry.ifIssue(new PartialDetector(), d -> new TextOnlyReport(),
                        TextOnlyReport::hasIssues, out),
                "a report type with no structuredViolations field is judged by its text by design");

        assertTrue(out.reports().containsKey("PartialDetector"), "the finding is still reported: " + out.reports());
    }

    @Test
    @DisplayName("not strict: the same report is reported as before, silently")
    void outsideStrictModeTheFindingIsReportedAndNothingIsWritten() {
        System.clearProperty(DetectorFailurePolicy.STRICT_PROPERTY);
        FindingSink out = new FindingSink();

        String written = captureStdErr(() -> assertDoesNotThrow(() ->
                DetectorRegistry.ifIssue(new PartialDetector(), d -> new StructuredReport(List.of()),
                        StructuredReport::hasIssues, out),
                "a library-internal inconsistency must not fail a consumer's run"));

        assertEquals("", written, "a consumer's build log gains nothing from a library-internal check");
        assertTrue(out.reports().containsKey("PartialDetector"), "the finding is still reported: " + out.reports());
        assertNull(out.severities().get("PartialDetector"),
                "with no structured finding the gate falls back to the text, as before");
    }

    private static String captureStdErr(Runnable body) {
        PrintStream previous = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            body.run();
        } finally {
            System.setErr(previous);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // ---- Fakes ----

    /** Stands in for a structured detector with one finding path that writes text only. */
    static final class PartialDetector {
    }

    /** A report type that keeps structured findings, as the built-in ones do. */
    public static final class StructuredReport {
        /** Read by name, like every built-in report's list. */
        public final List<Violation> structuredViolations;

        StructuredReport(List<Violation> findings) {
            this.structuredViolations = new ArrayList<>(findings);
        }

        boolean hasIssues() {
            return true;
        }

        @Override
        public String toString() {
            return "finding written as text";
        }
    }

    /** A report type with no structured list, as the detectors in TEXT_ONLY have. */
    static final class TextOnlyReport {
        boolean hasIssues() {
            return true;
        }

        @Override
        public String toString() {
            return "finding written as text";
        }
    }
}
