package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.spi.Detector;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code analyzeAll()} chains ~100 detector analyses with no exception handling anywhere in
 * the sweep. A single detector whose {@code analyze()} throws therefore aborts the whole sweep:
 * the accumulator built so far is discarded along with every finding already collected, and no
 * detector after the thrower in declaration order is ever run.
 *
 * <p>That is a live hazard, not a hypothetical — detectors accumulate state from N×M user
 * threads, so an unsynchronized iteration inside one of them can throw
 * {@link java.util.ConcurrentModificationException} — and third-party detectors arrive through
 * the public {@code Detector} SPI, where the library controls neither the code nor its bugs.
 *
 * <p>One flaky detector must not blank the run's findings. A detector that throws contributes
 * nothing; every other detector still reports.
 */
class DetectorSweepResilienceTest {

    /**
     * This project's own build runs with {@link DetectorFailurePolicy#STRICT_PROPERTY} on, so a
     * detector that throws fails the build rather than writing a line to stderr. These tests are
     * about the consumer-facing behaviour underneath that, so they run with it off and put it back
     * afterwards — {@link #strictModeTurnsAContainedFailureIntoAFailedBuild()} covers the other
     * half.
     */
    private void withoutStrictMode(Runnable body) {
        String previous = System.getProperty(DetectorFailurePolicy.STRICT_PROPERTY);
        System.clearProperty(DetectorFailurePolicy.STRICT_PROPERTY);
        try {
            body.run();
        } finally {
            if (previous != null) {
                System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, previous);
            }
        }
    }

    @AfterEach
    void restoreStrictMode() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
    }

    @Test
    void aThrowingDetectorDoesNotDiscardTheFindingsAlreadyCollected() {
        withoutStrictMode(this::assertThrowingDetectorIsContained);
    }

    private void assertThrowingDetectorIsContained() {
        FindingSink out = new FindingSink();

        DetectorRegistry.ifIssue(new GoodDetector("before"),
            GoodDetector::analyze, GoodDetector.Report::hasIssues, out);

        assertDoesNotThrow(() ->
            DetectorRegistry.ifIssue(new ExplodingDetector(),
                ExplodingDetector::analyze, r -> true, out),
            "a detector that throws must be contained, not allowed to abort the sweep");

        DetectorRegistry.ifIssue(new GoodDetector("after"),
            GoodDetector::analyze, GoodDetector.Report::hasIssues, out);

        assertEquals(1, out.reports().size(), "the good detector's findings must survive: " + out.reports());
        assertTrue(out.reports().get("GoodDetector").contains("before"),
            "the finding collected before the thrower must not be discarded: " + out.reports());
        assertTrue(out.reports().get("GoodDetector").contains("after"),
            "detectors after the thrower must still run: " + out.reports());
    }

    @Test
    void aDetectorThatThrowsWhileRenderingItsReportIsAlsoContained() {
        withoutStrictMode(this::assertUnrenderableReportIsContained);
    }

    private void assertUnrenderableReportIsContained() {
        FindingSink out = new FindingSink();

        assertDoesNotThrow(() ->
            DetectorRegistry.ifIssue(new ExplodingDetector(),
                d -> new ExplodingReport(), r -> true, out),
            "a report whose toString() throws must be contained too");

        assertTrue(out.reports().isEmpty(), "nothing to record when the report cannot be rendered: " + out.reports());
    }

    /**
     * The contained line is often all a CI log keeps of a detector failure: the run stays green and
     * nobody reruns it. A bare {@code ArrayIndexOutOfBoundsException: Index 1 out of bounds for
     * length 1} names no class, which is how #605 reached the corpus eval twice and could not be
     * traced from either log. The line has to say where the detector threw.
     */
    @Test
    void theSkipLineSaysWhereTheDetectorThrew() {
        withoutStrictMode(() -> {
            FindingSink out = new FindingSink();
            String written = captureStdErr(() ->
                DetectorRegistry.ifIssue(new ExplodingDetector(),
                    ExplodingDetector::analyze, r -> true, out));

            assertTrue(written.contains("ExplodingDetector failed during analysis and was skipped"),
                "the line keeps the shape the changelog and past logs quote: " + written);
            assertTrue(written.contains("ExplodingDetector.analyze("),
                "the line must name the frame that threw, not just the exception: " + written);
        });
    }

    /** Runs {@code body} with {@code System.err} redirected and returns what it wrote. */
    private static String captureStdErr(Runnable body) {
        java.io.PrintStream previous = System.err;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream capture = new java.io.PrintStream(
                buffer, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(capture);
            body.run();
        } finally {
            System.setErr(previous);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The other half of the contract. Containment is right for a consumer and wrong here: a
     * detector that throws reports nothing, and nothing reporting looks exactly like a clean run,
     * which is how five detectors shipped for several releases dereferencing a registry miss
     * inside {@code toString()}. Strict mode is what makes that red instead of quiet.
     */
    @Test
    void strictModeTurnsAContainedFailureIntoAFailedBuild() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        FindingSink out = new FindingSink();

        AssertionError raised = assertThrows(AssertionError.class, () ->
            DetectorRegistry.ifIssue(new ExplodingDetector(),
                ExplodingDetector::analyze, r -> true, out),
            "under strict mode a detector that throws must fail the build, not be swallowed");

        assertTrue(raised.getMessage().contains("ExplodingDetector"),
            "the failure must name the detector that broke: " + raised.getMessage());
        assertTrue(raised.getCause() instanceof java.util.ConcurrentModificationException,
            "the original failure must be preserved as the cause: " + raised.getCause());
    }

    @Test
    void strictModeAlsoCatchesAReportThatCannotBeRendered() {
        System.setProperty(DetectorFailurePolicy.STRICT_PROPERTY, "true");
        FindingSink out = new FindingSink();

        assertThrows(AssertionError.class, () ->
            DetectorRegistry.ifIssue(new ExplodingDetector(),
                d -> new ExplodingReport(), r -> true, out),
            "a finding lost while rendering is still a finding lost");
    }

    // ---- Fakes ----

    /** Stands in for any detector that reports a finding. */
    static final class GoodDetector {
        private final String marker;

        GoodDetector(String marker) {
            this.marker = marker;
        }

        Report analyze() {
            return new Report(marker);
        }

        record Report(String marker) {
            boolean hasIssues() {
                return true;
            }

            @Override
            public String toString() {
                return "finding: " + marker;
            }
        }
    }

    /** Stands in for a detector whose analysis blows up — a CME, an NPE, a third-party bug. */
    static final class ExplodingDetector implements Detector {
        @Override
        public DetectorType type() {
            return DetectorType.RACE_CONDITIONS;
        }

        @Override
        public List<Violation> analyze() {
            throw new java.util.ConcurrentModificationException("detector state mutated while analyzing");
        }
    }

    /** A report that cannot be rendered. */
    static final class ExplodingReport {
        @Override
        public String toString() {
            throw new IllegalStateException("report rendering blew up");
        }
    }
}
