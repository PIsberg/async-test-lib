package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical ABA — a lock-free stack head that sits at A, is swung to B, and is swung back
 * to A while another thread's CAS is in flight — produces exactly <b>two</b> recorded changes:
 * {@code A→B} and {@code B→A}. There is no {@code ?→A} change, because A was the value the
 * variable started with, not one it was ever written to.
 *
 * <p>{@code detectCycles} bailed out on {@code changes.size() < 3} and then matched a three-change
 * window ({@code ?→A}, {@code A→B}, {@code B→A}), so the minimal cycle — the one the detector is
 * named for — fell straight through the guard and was never counted.
 *
 * <p>The pattern that identifies an ABA is a value returning to what it just was: {@code A→B}
 * followed by {@code B→A}. Two changes are enough to see it.
 */
class AbaMinimalCycleTest {

    @Test
    void theMinimalAbaCycleIsDetected() {
        ABAProblemDetector detector = new ABAProblemDetector();

        // head: A -> B -> A. Two changes; A was the initial value, never written.
        detector.recordValueChange("head", "A", "B");
        detector.recordValueChange("head", "B", "A");

        ABAProblemDetector.ABAReport report = detector.analyzeABA();

        assertTrue(report.variablesWithCycles.containsKey("head"),
            "A -> B -> A is the ABA problem and must be counted: " + report.variablesWithCycles);
        assertTrue(report.hasIssues(), "the report must claim issues");
    }

    /** A value that keeps moving forward never returns to a prior value — no ABA. */
    @Test
    void aMonotonicSequenceOfChangesIsNotAnAba() {
        ABAProblemDetector detector = new ABAProblemDetector();

        detector.recordValueChange("counter", "1", "2");
        detector.recordValueChange("counter", "2", "3");
        detector.recordValueChange("counter", "3", "4");

        assertTrue(detector.analyzeABA().variablesWithCycles.isEmpty(),
            "a value that never comes back to a previous one is not an ABA: "
                + detector.analyzeABA().variablesWithCycles);
    }

    /** The longer form must keep working: a cycle that sits inside a longer history. */
    @Test
    void anAbaCycleInsideALongerHistoryIsStillDetected() {
        ABAProblemDetector detector = new ABAProblemDetector();

        detector.recordValueChange("head", "start", "A");
        detector.recordValueChange("head", "A", "B");
        detector.recordValueChange("head", "B", "A");

        assertTrue(detector.analyzeABA().variablesWithCycles.containsKey("head"),
            "the A -> B -> A cycle must still be found when preceded by other changes");
    }
}
