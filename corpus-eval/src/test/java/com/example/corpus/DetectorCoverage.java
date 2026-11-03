package com.example.corpus;

import se.deversity.asynctest.DetectorType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Which detectors the corpus pairs.
 *
 * <p>This class used to keep a list of detectors refused a pair, with a reason for each. Read
 * against their detectors on 2026-11-03, those reasons were out of date or named a silent half
 * nobody had written, as {@code docs/analysis/corpus-eval.md} sets out, so a detector with no pair
 * now fails {@code EveryDetectorIsPairedTest} and there is no list to add it to.
 */
final class DetectorCoverage {

    private DetectorCoverage() {
    }

    /** {@return every detector this corpus pairs in some lane} */
    static Set<DetectorType> paired() {
        return paired(Corpus::subjectsFor);
    }

    /**
     * {@return every detector paired by the rows {@code rowsOf} gives each lane}
     *
     * @param rowsOf the rows of a lane; {@link Corpus#subjectsFor} outside a test of this rule
     */
    static Set<DetectorType> paired(Function<CorpusLane, List<RecordingSubject>> rowsOf) {
        Set<DetectorType> paired = EnumSet.noneOf(DetectorType.class);
        for (CorpusLane lane : List.of(CorpusLane.RECORDING, CorpusLane.AGENT_PAIRS)) {
            for (RecordingSubject row : rowsOf.apply(lane)) {
                paired.add(row.detector());
            }
        }
        // The idiom lane pairs too (#836): #761 found a refusal outliving a pair there. Its twins
        // may name another detector than their correct row, and a known gap or a pinned note is a
        // correct row its detector still reports on, so a detector counts only with a firing row
        // and a correct row the lane holds to silence.
        Set<DetectorType> fires = EnumSet.noneOf(DetectorType.class);
        Set<DetectorType> silent = EnumSet.noneOf(DetectorType.class);
        for (RecordingSubject row : rowsOf.apply(CorpusLane.IDIOMS)) {
            if (row.expectation() == RecordingSubject.Expectation.MUST_FIRE) {
                fires.add(row.detector());
            } else if (row.expectedSeverity() == null
                    && !Corpus.idiomKnownGaps().containsKey(row.testMethod())) {
                silent.add(row.detector());
            }
        }
        fires.retainAll(silent);
        paired.addAll(fires);
        // Lane one pairs these over its unmodified subjects, firing on documented-unsafe and silent
        // on every documented-safe one, which is a stronger measurement than a two-row pair, not a
        // weaker one. CorpusGates owns the set so the two cannot drift apart.
        paired.addAll(CorpusGates.exercisedAgentDetectors());
        return paired;
    }
}
