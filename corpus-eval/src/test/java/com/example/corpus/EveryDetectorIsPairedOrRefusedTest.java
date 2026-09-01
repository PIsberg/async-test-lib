package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;

import java.util.EnumSet;
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

    private static String names(Set<DetectorType> types) {
        Set<String> sorted = new TreeSet<>();
        for (DetectorType type : types) {
            sorted.add(type.name());
        }
        return sorted.toString();
    }
}
