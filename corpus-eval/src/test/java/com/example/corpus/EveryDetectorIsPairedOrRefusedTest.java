package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.IssueSeverity;

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

    /**
     * #761 paired THREAD_LOCAL_RANDOM_MISUSE in the idiom lane and its refusal stood, because
     * {@link DetectorCoverage#paired()} read only the other lanes. The refused detector here is
     * paired by a synthetic idiom pair and nothing else, which is that case.
     */
    @Test
    @DisplayName("a pair in the idiom lane counts, so a refusal cannot outlive it there")
    void anIdiomLanePairCounts() {
        DetectorType refused = DetectorCoverage.refused().keySet().iterator().next();
        List<RecordingSubject> idioms = List.of(
                idiomRow("idiom_synthetic_correct", refused, RecordingSubject.Expectation.MUST_STAY_SILENT),
                idiomRow("idiom_synthetic_broken", refused, RecordingSubject.Expectation.MUST_FIRE));

        Set<DetectorType> paired = DetectorCoverage.paired(
                lane -> lane == CorpusLane.IDIOMS ? idioms : List.of());

        assertTrue(paired.contains(refused),
                refused + " has both directions in the idiom lane and is not counted as paired, so "
                        + "noRefusalOutlivesItsPair cannot see its refusal go stale: " + names(paired));
    }

    /**
     * An idiom twin may name another detector than its correct row (a shared Random's LOW note
     * beside a SplittableRandom that fires), and a known gap is a correct row that still fires, so
     * neither is half of a pair for the detector it names.
     */
    @Test
    @DisplayName("an idiom row without its other direction in that lane pairs nothing")
    void anIdiomRowWithoutItsOtherDirectionPairsNothing() {
        List<DetectorType> refused = List.copyOf(DetectorCoverage.refused().keySet());
        DetectorType silentOnly = refused.get(0);
        DetectorType fireOnly = refused.get(1);
        DetectorType noteOnly = refused.get(2);
        List<RecordingSubject> idioms = List.of(
                idiomRow("idiom_synthetic_correct", silentOnly, RecordingSubject.Expectation.MUST_STAY_SILENT),
                idiomRow("idiom_synthetic_broken", fireOnly, RecordingSubject.Expectation.MUST_FIRE),
                new RecordingSubject("idiom_synthetic_note", "jdk:java.base", "java.lang.Object",
                        noteOnly, Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT,
                        "synthetic", IssueSeverity.LOW),
                idiomRow("idiom_synthetic_noteTwin", noteOnly, RecordingSubject.Expectation.MUST_FIRE));

        Set<DetectorType> paired = DetectorCoverage.paired(
                lane -> lane == CorpusLane.IDIOMS ? idioms : List.of());

        assertTrue(!paired.contains(silentOnly) && !paired.contains(fireOnly)
                        && !paired.contains(noteOnly),
                "a detector with one direction in the idiom lane, or a note where the silent half "
                        + "should be, is counted as paired: " + names(paired));
    }

    private static RecordingSubject idiomRow(String method,
                                             DetectorType detector,
                                             RecordingSubject.Expectation expectation) {
        return new RecordingSubject(method, "jdk:java.base", "java.lang.Object", detector,
                Contract.THREAD_SAFE, expectation, "synthetic");
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
