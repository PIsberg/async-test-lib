package se.deversity.asynctest.spi.adapters;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.ThreadLocalCacheDegradationDetector;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.spi.Detector;
import se.deversity.asynctest.spi.DetectorRegistry;
import se.deversity.asynctest.spi.adapters.fixture.HiddenReportDetector;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the SPI path, {@code spi.DetectorRegistry.build}, hands its caller for a built-in detector
 * (#841).
 *
 * <p>The adapter invokes the detector reflectively, so everything the detector throws arrives
 * wrapped in an {@code InvocationTargetException}. It used to catch that as a reflection failure
 * and return nothing: a detector whose {@code analyze()} threw, including the strict-mode
 * {@code AssertionError} of {@link DetectorFailurePolicy#checkedReport}, reported nothing and
 * failed nothing, which is exactly the silent green {@link DetectorFailurePolicy} exists to
 * prevent. It also graded every finding {@code HIGH}, so a detector that rated its finding
 * {@code MEDIUM} disagreed with the {@code failOn} gate reading the same report.
 */
@DisplayName("LegacyDetectorAdapter: detector failures and structured severities reach the SPI")
class LegacyDetectorAdapterTest {

    private String strictBefore;

    @BeforeEach
    void rememberStrictMode() {
        strictBefore = System.getProperty(DetectorFailurePolicy.STRICT_PROPERTY);
    }

    @AfterEach
    void restoreStrictMode() {
        if (strictBefore == null) {
            System.clearProperty(DetectorFailurePolicy.STRICT_PROPERTY);
        } else {
            System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, strictBefore);
        }
    }

    @Test
    @DisplayName("strict: a detector whose analyze() throws fails the build, naming the detector and its own exception")
    void strictModeFailsADetectorThatThrows() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        Detector adapter = new LegacyDetectorAdapter<>(new ThrowingDetector(), DetectorType.DEADLOCKS, "Throwing");

        AssertionError raised = assertThrows(AssertionError.class, adapter::analyze,
                "a detector that throws reports nothing, which looks like a clean run");

        assertTrue(raised.getMessage().contains("ThrowingDetector"),
                "the failure must name the detector: " + raised.getMessage());
        assertInstanceOf(IllegalStateException.class, raised.getCause(),
                "the cause is what the detector threw, not the reflection wrapper around it");
    }

    @Test
    @DisplayName("strict: a report's hasIssues() that throws fails the build the same way")
    void strictModeFailsAReportWhoseHasIssuesThrows() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        Detector adapter = new LegacyDetectorAdapter<>(new BrokenReportDetector(), DetectorType.DEADLOCKS, "Broken");

        AssertionError raised = assertThrows(AssertionError.class, adapter::analyze);

        assertInstanceOf(IllegalStateException.class, raised.getCause(),
                "the cause is what hasIssues() threw: " + raised.getCause());
    }

    @Test
    @DisplayName("strict: an AssertionError raised inside analyze() reaches the caller")
    void aStrictModeAssertionInsideAnalyzePropagates() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        Detector adapter = new LegacyDetectorAdapter<>(new EmptyStructuredListDetector(), DetectorType.DEADLOCKS,
                "EmptyStructuredList");

        AssertionError raised = assertThrows(AssertionError.class, adapter::analyze,
                "checkedReport's failure must not be swallowed on the SPI path");

        assertTrue(raised.getMessage().contains("structuredViolations"),
                "it is checkedReport's own failure, not a rewrapped one: " + raised.getMessage());
    }

    @Test
    @DisplayName("not strict: the failure is contained, reports nothing, and writes the policy's line")
    void outsideStrictModeTheFailureIsContainedAndLogged() {
        System.clearProperty(DetectorFailurePolicy.STRICT_PROPERTY);
        Detector adapter = new LegacyDetectorAdapter<>(new ThrowingDetector(), DetectorType.DEADLOCKS, "Throwing");

        List<List<Violation>> result = new ArrayList<>();
        String written = captureStdErr(() -> result.add(adapter.analyze()));

        assertTrue(result.get(0).isEmpty(), "a broken detector costs its own finding: " + result.get(0));
        assertTrue(written.contains("[AsyncTest] Detector ThrowingDetector failed during analysis and was skipped: "
                        + "java.lang.IllegalStateException: analysis broke"),
                "the same line the registry path writes, naming the detector: " + written);
    }

    @Test
    @DisplayName("a MEDIUM structured finding from a built-in detector comes out MEDIUM through spi.DetectorRegistry.build")
    void aStructuredFindingKeepsItsSeverity() throws InterruptedException {
        AsyncTestConfig config = AsyncTestConfig.builder().detectAll(true).build();
        Detector detector = DetectorRegistry.build(config).get(DetectorType.THREAD_LOCAL_CACHE_DEGRADATION);
        ThreadLocalCacheDegradationDetector legacy =
                (ThreadLocalCacheDegradationDetector) ((LegacyDetectorAdapter<?>) detector).delegate();

        // One fresh instance per virtual thread: nothing is reused, which the detector rates MEDIUM.
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            threads.add(Thread.ofVirtual().start(() ->
                    legacy.recordCachedValue("FORMAT", new StringBuilder(), Thread.currentThread())));
        }
        for (Thread t : threads) {
            t.join();
        }

        List<Violation> violations = detector.analyze();

        assertFalse(violations.isEmpty(), "six threads, six instances, nothing reused must fire");
        for (Violation v : violations) {
            assertEquals(IssueSeverity.MEDIUM, v.severity(),
                    "the detector rated this finding MEDIUM, and the failOn gate reads MEDIUM: " + v);
            assertEquals("ThreadLocalCacheDegradation", v.detector());
        }
    }

    @Test
    @DisplayName("every structured finding is handed over at its own severity")
    void eachStructuredFindingIsHandedOver() {
        Detector adapter = new LegacyDetectorAdapter<>(new TwoFindingsDetector(), DetectorType.DEADLOCKS, "TwoFindings");

        List<Violation> violations = adapter.analyze();

        assertEquals(List.of(IssueSeverity.MEDIUM, IssueSeverity.LOW),
                violations.stream().map(Violation::severity).toList(), "findings: " + violations);
        assertEquals(List.of("first", "second"), violations.stream().map(Violation::message).toList());
    }

    @Test
    @DisplayName("a text-only report is one finding at the severity its text marks")
    void aTextOnlyReportIsGradedByItsText() {
        Detector adapter = new LegacyDetectorAdapter<>(new TextOnlyDetector(), DetectorType.DEADLOCKS, "TextOnly");

        List<Violation> violations = adapter.analyze();

        assertEquals(1, violations.size(), "findings: " + violations);
        assertEquals(IssueSeverity.LOW, violations.get(0).severity(),
                "the text marks [LOW], which is what the failOn gate reads: " + violations);
        assertEquals(TextOnlyDetector.TEXT, violations.get(0).message());
    }

    @Test
    @DisplayName("an empty structured list is held to strict mode as on the registry path, and reported as text outside it")
    void anEmptyStructuredListFallsBackToTextAndFailsStrictMode() {
        Detector adapter = new LegacyDetectorAdapter<>(new UncheckedEmptyListDetector(), DetectorType.DEADLOCKS,
                "UncheckedEmptyList");

        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        AssertionError raised = assertThrows(AssertionError.class, adapter::analyze,
                "a report with issues and an empty list reaches the gate as a guess from its text");
        assertTrue(raised.getMessage().contains("UncheckedEmptyListDetector"),
                "the failure must name the detector: " + raised.getMessage());

        System.clearProperty(DetectorFailurePolicy.STRICT_PROPERTY);
        List<Violation> violations = adapter.analyze();
        assertEquals(1, violations.size(), "the finding is still reported: " + violations);
        assertEquals("findings written as text", violations.get(0).message());
    }

    @Test
    @DisplayName("strict: a report the adapter cannot reach fails the build instead of reporting nothing (#847)")
    void strictModeFailsAReportTheAdapterCannotReach() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        Detector adapter = new LegacyDetectorAdapter<>(new HiddenReportDetector(), DetectorType.DEADLOCKS, "Hidden");

        AssertionError raised = assertThrows(AssertionError.class, adapter::analyze,
                "a report whose hasIssues() the adapter may not call reports nothing, which looks like a clean run");

        assertTrue(raised.getMessage().contains("HiddenReportDetector"),
                "the failure must name the detector: " + raised.getMessage());
        assertInstanceOf(IllegalAccessException.class, raised.getCause(),
                "the cause says why the report could not be read: " + raised.getCause());
    }

    @Test
    @DisplayName("not strict: an unreachable report is contained and writes the policy's line")
    void outsideStrictModeAnUnreachableReportIsLogged() {
        System.clearProperty(DetectorFailurePolicy.STRICT_PROPERTY);
        Detector adapter = new LegacyDetectorAdapter<>(new HiddenReportDetector(), DetectorType.DEADLOCKS, "Hidden");

        List<List<Violation>> result = new ArrayList<>();
        String written = captureStdErr(() -> result.add(adapter.analyze()));

        assertTrue(result.get(0).isEmpty(), "the finding cannot be read: " + result.get(0));
        assertTrue(written.contains("[AsyncTest] Detector HiddenReportDetector failed during analysis and was skipped: "
                        + "java.lang.IllegalAccessException"),
                "the line names the detector and the refused access: " + written);
    }

    @Test
    @DisplayName("strict: a detector with no report method is not a failure; it has no view on this path, and says nothing")
    void aShapelessDetectorStaysSilentEvenUnderStrictMode() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        Detector adapter = new LegacyDetectorAdapter<>(new ShapelessDetector(), DetectorType.DEADLOCKS, "Shapeless");

        List<List<Violation>> result = new ArrayList<>();
        String written = captureStdErr(() -> result.add(adapter.analyze()));

        assertTrue(result.get(0).isEmpty(), "nothing to report from: " + result.get(0));
        assertEquals("", written, "a detector without the canonical shape is not a broken one");
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

    // ---- Fakes, public like every built-in detector and report, since the adapter reads them reflectively ----

    /** A detector whose analysis throws, as the five that dereferenced a registry miss did. */
    public static final class ThrowingDetector {
        public TextReport analyze() {
            throw new IllegalStateException("analysis broke");
        }
    }

    /** A detector with no report method at all, which built-ins are barred from by DetectorFiringContractTest. */
    public static final class ShapelessDetector {
        public void record() {
            // records into nothing; there is no analyze() to read it back
        }
    }

    /** A detector whose report throws when asked whether it has issues. */
    public static final class BrokenReportDetector {
        public BrokenReport analyze() {
            return new BrokenReport();
        }
    }

    /** A structured detector with a finding path that forgot its Violation. */
    public static final class EmptyStructuredListDetector {
        public StructuredReport analyze() {
            return DetectorFailurePolicy.checkedReport(this, new StructuredReport(List.of()));
        }
    }

    /** The same, built without {@code checkedReport}, so only the adapter can notice. */
    public static final class UncheckedEmptyListDetector {
        public StructuredReport analyze() {
            return new StructuredReport(List.of());
        }
    }

    /** A structured detector with two findings at different severities. */
    public static final class TwoFindingsDetector {
        public StructuredReport analyze() {
            return new StructuredReport(List.of(
                    new Violation("TwoFindings", IssueSeverity.MEDIUM, "first", List.of(), Map.of(), null),
                    new Violation("TwoFindings", IssueSeverity.LOW, "second", List.of(), Map.of(), null)));
        }
    }

    /** A detector whose report has no structured list, as the text-only built-ins have. */
    public static final class TextOnlyDetector {
        static final String TEXT = "[LOW] one finding written as text";

        public TextReport analyze() {
            return new TextReport();
        }
    }

    /** A text-only report that always has issues. */
    public static final class TextReport {
        public boolean hasIssues() {
            return true;
        }

        @Override
        public String toString() {
            return TextOnlyDetector.TEXT;
        }
    }

    /** A report whose predicate throws. */
    public static final class BrokenReport {
        public boolean hasIssues() {
            throw new IllegalStateException("predicate broke");
        }
    }

    /** A report that keeps structured findings beside its text, read by name like the built-ins. */
    public static final class StructuredReport {
        public final List<Violation> structuredViolations;

        StructuredReport(List<Violation> findings) {
            this.structuredViolations = new ArrayList<>(findings);
        }

        public boolean hasIssues() {
            return true;
        }

        @Override
        public String toString() {
            return "findings written as text";
        }
    }
}
