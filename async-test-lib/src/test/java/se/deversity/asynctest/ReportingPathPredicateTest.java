package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.VirtualThreadPinningDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The predicate {@code DetectorRegistry.analyzeAllNamed()} binds decides what a user is told,
 * and it must be the same predicate every other consumer of the report binds to.
 *
 * <p><strong>Why this exists.</strong> A report class can expose several boolean questions -
 * {@code hasPinningIssues()}, {@code hasEffectivePinningIssues()}, {@code hasLeaks()},
 * {@code hasFairnessIssues()} - and only one of them answers "should the user see a finding?".
 * That one is {@code hasIssues()}: it is what {@code LegacyDetectorAdapter} resolves for the SPI
 * {@code Violation} pipeline, and what {@link DetectorFiringContractTest} requires every detector
 * to expose. The legacy reporting path picks its predicate by hand, once per detector, in a chain
 * of ~135 {@code ifIssue(...)} lines. Nothing made the two agree.
 *
 * <p>They had already drifted. {@code PinningReport.hasIssues()} deliberately delegates to
 * {@code hasEffectivePinningIssues()} so that an event whose cause no longer pins on the running
 * JDK - {@code synchronized} since JEP 491 in JDK 24 - is not reported as a defect the user
 * cannot act on. The reporting path bound {@code hasPinningIssues()}, which counts those events,
 * so the fix never reached the report a user actually reads.
 *
 * <p>This is the same shape as the bug that made {@code RaceConditionDetector} unable to see the
 * README's example: every component was individually correct and tested, and the seam between
 * them was not. The detector's own unit tests pass either way, because they call the report's
 * methods directly and never ask which one the report path uses.
 */
class ReportingPathPredicateTest {

    /** The JDK feature version this test is running on; decides whether MONITOR still pins. */
    private static final int JDK = Runtime.version().feature();

    @Test
    @DisplayName("a pinning event that no longer pins on this JDK does not reach the user's report")
    void obsoletePinningEventIsNotReportedThroughTheUserFacingPath() throws Exception {
        AsyncTestConfig config = AsyncTestConfig.builder().detectAll(true).build();
        AsyncTestContext ctx = new AsyncTestContext(config);

        VirtualThreadPinningDetector detector = ctx.virtualThreadPinningDetector;
        assertTrue(detector != null,
                "precondition: detectAll must enable the pinning detector, or this proves nothing");
        detector.startMonitoring();

        // Record from inside a real virtual thread: recordPinningEvent() drops anything else.
        Thread vt = Thread.ofVirtual().unstarted(
                () -> detector.recordPinningEvent(Thread.currentThread(), "synchronized block"));
        vt.start();
        vt.join();

        VirtualThreadPinningDetector.PinningReport report = detector.analyzePinning();
        assertTrue(report.getEvents().size() == 1,
                "precondition: the event must have been recorded, or neither branch below means "
                        + "anything. Recorded: " + report.getEvents().size());

        Map<String, String> reported = ctx.analyzeAllNamed();
        boolean surfaced = reported.containsKey("VirtualThreadPinningDetector");

        if (JDK >= 24) {
            assertFalse(surfaced,
                    "JEP 491 made synchronized non-pinning from JDK 24, and this JVM is "
                            + JDK + ". PinningReport.hasIssues() returns " + report.hasIssues()
                            + " for exactly that reason, but the reporting path bound "
                            + "hasPinningIssues() (" + report.hasPinningIssues() + "), so the "
                            + "user is told to fix a synchronized block that costs them nothing "
                            + "on the JDK they are running. Bind hasIssues() in "
                            + "DetectorRegistry.analyzeAllNamed(). Reported: " + reported.keySet());
        } else {
            assertTrue(surfaced,
                    "On JDK " + JDK + " a monitor still pins the carrier, so this is a genuine "
                            + "finding and suppressing it would be a false negative. Reported: "
                            + reported.keySet());
        }
    }

    /**
     * The general form: no {@code ifIssue(...)} line may bind a predicate other than
     * {@code hasIssues()}.
     *
     * <p>Source-level rather than behavioural, and deliberately so - a behavioural check would
     * need a report per detector that distinguishes the two predicates, which is exactly the
     * fixture-per-detector cost this gate exists to avoid. What it proves is narrower and still
     * decisive: the reporting path and the SPI pipeline ask the same question. Three of the four
     * hand-picked predicates happened to be aliases of {@code hasIssues()} when this was written;
     * that is a coincidence of the code at one moment, not a property anyone was maintaining.
     */
    @Test
    @DisplayName("every ifIssue binding uses hasIssues(), the predicate the SPI pipeline binds")
    void reportingPathBindsTheCanonicalPredicate() throws IOException {
        Path registry = Path.of("src/main/java/se/deversity/asynctest/DetectorRegistry.java");
        assertTrue(Files.isRegularFile(registry),
                "DetectorRegistry moved; this gate stopped inspecting anything. Fix the path.");

        String source = Files.readString(registry, StandardCharsets.UTF_8);

        // The third argument of each ifIssue(...) call: Owner.Report::predicate
        Matcher m = Pattern.compile("([A-Za-z0-9_]+\\.[A-Za-z0-9_]+)::(has[A-Za-z0-9_]+)")
                .matcher(source);

        List<String> divergent = new ArrayList<>();
        int bindings = 0;
        while (m.find()) {
            bindings++;
            if (!"hasIssues".equals(m.group(2))) {
                divergent.add(m.group(1) + "::" + m.group(2));
            }
        }

        assertTrue(bindings > 100,
                "Found only " + bindings + " report-predicate bindings in analyzeAllNamed(). The "
                        + "chain holds one per detector, so the pattern has stopped matching and "
                        + "this gate is inspecting nothing. Fix the pattern, not the number.");

        assertTrue(divergent.isEmpty(),
                "These bindings ask a different question than hasIssues():\n  "
                        + String.join("\n  ", divergent)
                        + "\n\nhasIssues() is the canonical predicate: LegacyDetectorAdapter "
                        + "resolves it for the SPI Violation pipeline and DetectorFiringContractTest "
                        + "requires every report to expose it. When the reporting path binds "
                        + "something else, a detector can be fixed on one path and not the other "
                        + "with every test still green - which is how PinningReport's JDK 24+ "
                        + "false-positive fix stopped short of the report users read.\n\n"
                        + "If the alternative predicate is the correct answer, make hasIssues() "
                        + "delegate to it and bind hasIssues() here.");
    }
}
