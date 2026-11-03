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
 * Holds the corpus to pairing every detector the library ships.
 *
 * <p>Adding a {@code DetectorType} fails here until a row must fire for it and a twin must stay
 * silent. There used to be a second way out, a written refusal, and the last of those were lifted on
 * 2026-11-03, so the document's exhaustiveness claim no longer has an exception to go stale.
 *
 * <p>A second check holds the pairs themselves to being pairs. {@code Corpus.pairedDetectors}
 * counts a detector as paired if any row names it, direction ignored, so a lone
 * {@code MUST_FIRE} row satisfied the accounting above while proving nothing: the README's
 * own rule for adding a row says one direction is passed by a detector that fires on
 * everything. Every detector the pair lanes name already has both halves, so that check is a
 * ratchet rather than a fix.
 *
 * <p>It runs in the agent-on lane because it needs no run at all - it reads {@link Corpus}'s subject
 * lists, which are static. Putting it in a lane that executes bodies would make a bookkeeping check
 * depend on a measurement.
 */
class EveryDetectorIsPairedTest {

    @Test
    @DisplayName("every detector is paired in some lane")
    void everyDetectorIsPaired() {
        Set<DetectorType> unaccounted = EnumSet.allOf(DetectorType.class);
        unaccounted.removeAll(DetectorCoverage.paired());

        assertTrue(unaccounted.isEmpty(),
                "these detectors have no corpus row that must fire and twin that must stay silent, "
                        + "so nothing shows them working on a real call: " + names(unaccounted));
    }

    /**
     * #761 paired THREAD_LOCAL_RANDOM_MISUSE in the idiom lane and nothing counted it, because
     * {@link DetectorCoverage#paired()} read only the other lanes. The detector here is paired by a
     * synthetic idiom pair and nothing else, which is that case.
     */
    @Test
    @DisplayName("a pair in the idiom lane counts")
    void anIdiomLanePairCounts() {
        DetectorType detector = notPairedByLaneOne().get(0);
        List<RecordingSubject> idioms = List.of(
                idiomRow("idiom_synthetic_correct", detector, RecordingSubject.Expectation.MUST_STAY_SILENT),
                idiomRow("idiom_synthetic_broken", detector, RecordingSubject.Expectation.MUST_FIRE));

        Set<DetectorType> paired = DetectorCoverage.paired(
                lane -> lane == CorpusLane.IDIOMS ? idioms : List.of());

        assertTrue(paired.contains(detector),
                detector + " has both directions in the idiom lane and is not counted as paired: "
                        + names(paired));
    }

    /**
     * An idiom twin may name another detector than its correct row (a shared Random's LOW note
     * beside a SplittableRandom that fires), and a known gap is a correct row that still fires, so
     * neither is half of a pair for the detector it names.
     */
    @Test
    @DisplayName("an idiom row without its other direction in that lane pairs nothing")
    void anIdiomRowWithoutItsOtherDirectionPairsNothing() {
        List<DetectorType> detectors = notPairedByLaneOne();
        DetectorType silentOnly = detectors.get(0);
        DetectorType fireOnly = detectors.get(1);
        DetectorType noteOnly = detectors.get(2);
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

    /** {@return detectors only a synthetic row can pair here, since lane one's count is always added} */
    private static List<DetectorType> notPairedByLaneOne() {
        Set<DetectorType> detectors = EnumSet.allOf(DetectorType.class);
        detectors.removeAll(CorpusGates.exercisedAgentDetectors());
        return List.copyOf(detectors);
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
