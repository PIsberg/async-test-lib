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
 * Holds {@link LibraryReach} to accounting for every agent-fed detector.
 *
 * <p>The same arrangement as {@link EveryDetectorIsPairedOrRefusedTest}, one level down. That one
 * asks whether a detector is paired anywhere; this asks whether an agent-fed detector has been
 * measured where users meet it, on a call site inside a library rather than in a test file. A
 * detector the library starts feeding from the agent fails here until a library pair reaches it or
 * a reason says why none can, and a reason fails once a pair makes it untrue.
 *
 * <p>Static, like its sibling: it reads the rows and the reasons and executes no body.
 */
class EveryAgentFedDetectorIsReachedThroughALibraryTest {

    @Test
    @DisplayName("every agent-fed detector is reached through a library or says why not")
    void everyAgentFedDetectorIsReachedOrExplained() {
        Set<DetectorType> unaccounted = LibraryReach.agentFed();
        unaccounted.removeAll(LibraryReach.reached());
        unaccounted.removeAll(LibraryReach.unreached().keySet());

        assertTrue(unaccounted.isEmpty(),
                "these agent-fed detectors have no pair whose call site is inside a library and "
                        + "no written reason, so nothing says whether the agent sees their call "
                        + "anywhere but in a test file: " + names(unaccounted));
    }

    @Test
    @DisplayName("no reason outlives the library pair that disproves it")
    void noReasonOutlivesItsPair() {
        Set<DetectorType> both = EnumSet.noneOf(DetectorType.class);
        both.addAll(LibraryReach.unreached().keySet());
        both.retainAll(LibraryReach.reached());

        assertTrue(both.isEmpty(),
                "these detectors are reached through a library and listed as unreachable at the "
                        + "same time - delete the entry: " + names(both));
    }

    @Test
    @DisplayName("a reason is only for agent-fed detectors")
    void everyReasonNamesAnAgentFedDetector() {
        Set<DetectorType> stray = EnumSet.noneOf(DetectorType.class);
        stray.addAll(LibraryReach.unreached().keySet());
        stray.removeAll(LibraryReach.agentFed());

        assertTrue(stray.isEmpty(),
                "these detectors are not fed by the agent, so whether a library call site reaches "
                        + "them is not a question the agent-pair lane can answer: " + names(stray));
    }

    @Test
    @DisplayName("a library row's twin is a row of the same library class")
    void everyLibraryPairIsALibraryPair() {
        List<String> broken = new ArrayList<>();
        for (RecordingSubject loud : Corpus.subjectsFor(CorpusLane.AGENT_PAIRS)) {
            if (loud.expectation() != RecordingSubject.Expectation.MUST_FIRE
                    || loud.library().startsWith("jdk:")) {
                continue;
            }
            RecordingSubject quiet = AgentRowPremise.twinOf(loud);
            if (quiet == null) {
                broken.add(loud.testMethod() + " has no MUST_STAY_SILENT row of "
                        + loud.className() + " for " + loud.detector());
            } else if (!quiet.library().equals(loud.library())) {
                broken.add(loud.testMethod() + " is " + loud.library() + " and its twin "
                        + quiet.testMethod() + " is " + quiet.library());
            }
        }

        assertTrue(broken.isEmpty(),
                "a library pair is evidence about a library call site only if both halves go "
                        + "through that library: " + broken);
    }

    private static String names(Set<DetectorType> types) {
        Set<String> sorted = new TreeSet<>();
        for (DetectorType type : types) {
            sorted.add(type.name());
        }
        return sorted.toString();
    }
}
