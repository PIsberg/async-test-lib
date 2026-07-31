package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.spi.Detector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    void aThrowingDetectorDoesNotDiscardTheFindingsAlreadyCollected() {
        Map<String, String> out = new LinkedHashMap<>();

        DetectorRegistry.ifIssue(new GoodDetector("before"),
            GoodDetector::analyze, GoodDetector.Report::hasIssues, out);

        assertDoesNotThrow(() ->
            DetectorRegistry.ifIssue(new ExplodingDetector(),
                ExplodingDetector::analyze, r -> true, out),
            "a detector that throws must be contained, not allowed to abort the sweep");

        DetectorRegistry.ifIssue(new GoodDetector("after"),
            GoodDetector::analyze, GoodDetector.Report::hasIssues, out);

        assertEquals(1, out.size(), "the good detector's findings must survive: " + out);
        assertTrue(out.get("GoodDetector").contains("before"),
            "the finding collected before the thrower must not be discarded: " + out);
        assertTrue(out.get("GoodDetector").contains("after"),
            "detectors after the thrower must still run: " + out);
    }

    @Test
    void aDetectorThatThrowsWhileRenderingItsReportIsAlsoContained() {
        Map<String, String> out = new LinkedHashMap<>();

        assertDoesNotThrow(() ->
            DetectorRegistry.ifIssue(new ExplodingDetector(),
                d -> new ExplodingReport(), r -> true, out),
            "a report whose toString() throws must be contained too");

        assertTrue(out.isEmpty(), "nothing to record when the report cannot be rendered: " + out);
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
