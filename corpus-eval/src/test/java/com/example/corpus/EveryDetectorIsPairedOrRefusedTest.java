package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the corpus to accounting for every detector the library ships.
 *
 * <p>This is the check {@code docs/analysis/corpus-eval.md} has twice described as "one command"
 * without anyone being able to run it. Adding a {@code DetectorType} now fails here until it is
 * either paired or refused in writing, which is the only arrangement under which the document's
 * exhaustiveness claim stays true without someone remembering to re-derive it.
 *
 * <p>A third check holds the pairs themselves to being pairs. {@code Corpus.pairedDetectors}
 * counts a detector as paired if any row names it, direction ignored, so a lone
 * {@code MUST_FIRE} row satisfied the accounting above while proving nothing: the README's
 * own rule for adding a row says one direction is passed by a detector that fires on
 * everything. Every detector the pair lanes name already has both halves, so that check is a
 * ratchet rather than a fix.
 *
 * <p>It runs in the agent-on lane because it needs no run at all - it reads
 * {@link Corpus}'s subject lists and {@link DetectorCoverage}'s refusals, both of which are
 * static. Putting it in a lane that executes bodies would make a bookkeeping check depend on a
 * measurement.
 */
class EveryDetectorIsPairedOrRefusedTest {

    @Test
    @DisplayName("every detector is paired in some lane or refused with a reason")
    void everyDetectorIsPairedOrRefused() {
        Set<DetectorType> unaccounted = EnumSet.allOf(DetectorType.class);
        unaccounted.removeAll(DetectorCoverage.paired());
        unaccounted.removeAll(DetectorCoverage.refused().keySet());

        assertTrue(unaccounted.isEmpty(),
                "these detectors are neither paired by a corpus row nor refused with a reason, so "
                        + "the corpus reports no rate for them and nothing records why: "
                        + names(unaccounted));
    }

    @Test
    @DisplayName("no detector is refused after it has been paired")
    void noRefusalOutlivesItsPair() {
        Set<DetectorType> both = EnumSet.noneOf(DetectorType.class);
        both.addAll(DetectorCoverage.refused().keySet());
        both.retainAll(DetectorCoverage.paired());

        assertTrue(both.isEmpty(),
                "these detectors have a pair and a refusal at the same time. A refusal is written "
                        + "once and revisited by nothing, so a stale one reads as a standing "
                        + "limitation long after the limitation is gone - delete the entry: "
                        + names(both));
    }

    @Test
    @DisplayName("every detector a pair lane names has both directions in that lane")
    void everyPairIsAPair() {
        List<String> lopsided = new ArrayList<>();
        for (CorpusLane lane : List.of(CorpusLane.RECORDING, CorpusLane.AGENT_PAIRS)) {
            for (DetectorType detector : Corpus.pairedDetectors(lane)) {
                Set<RecordingSubject.Expectation> directions =
                        EnumSet.noneOf(RecordingSubject.Expectation.class);
                for (RecordingSubject subject : Corpus.subjectsFor(lane)) {
                    if (subject.detector() == detector) {
                        directions.add(subject.expectation());
                    }
                }
                if (directions.size() != 2) {
                    lopsided.add(detector + " in the " + lane.propertyValue() + " lane has only "
                            + directions);
                }
            }
        }

        assertTrue(lopsided.isEmpty(),
                "the README's rule for adding a row is that rows come in pairs, and until now "
                        + "nothing enforced it: Corpus.pairedDetectors counts a detector as paired "
                        + "if any row names it, direction ignored. One direction proves nothing - "
                        + "a lone MUST_FIRE row is passed by a detector that fires on everything, "
                        + "and a lone MUST_STAY_SILENT row by one that was never wired up. These "
                        + "detectors are counted as paired on half a pair: " + lopsided);
    }

    private static String names(Set<DetectorType> types) {
        Set<String> sorted = new TreeSet<>();
        for (DetectorType type : types) {
            sorted.add(type.name());
        }
        return sorted.toString();
    }
}
