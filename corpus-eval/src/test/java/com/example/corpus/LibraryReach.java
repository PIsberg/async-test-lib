package com.example.corpus;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.DetectorFeeds;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which agent-fed detectors are measured on a call site inside a library, and why the rest are not.
 *
 * <p>An agent-fed detector hears about a JDK call because the weaver rewrote the instruction that
 * makes it. The agent-pair lane first wrote those calls in its own test file, which measures the
 * detector's model and leaves open the question a user actually has: is the call still seen when it
 * is three frames down, in a jar nobody on this project compiled? A pair whose body calls only a
 * library method answers that, and so does lane one, whose bodies call only the library too.
 *
 * <p>The claim lives here as data for the reason {@link DetectorCoverage} gives for its own: a
 * list in prose is true when written and quietly false a month later.
 * {@code EveryAgentFedDetectorIsReachedThroughALibraryTest} holds it to both directions.
 *
 * <p>Checked by exclusion on every run rather than assumed: the {@code agent-pairs-library-excluded}
 * lane runs the library rows with those libraries on the agent's exclude list, and every library
 * MUST_FIRE row must go silent there, so each of these findings came from the library's bytecode
 * ({@link CorpusGates#checkLibraryExclusionLane}, #544).
 */
final class LibraryReach {

    /** Agent-fed detectors no corpus library reaches, each with what stops it. */
    private static final Map<DetectorType, String> UNREACHED = new EnumMap<>(DetectorType.class);

    static {
        unreached(DetectorType.MISSED_SIGNAL,
                "a firing library row needs a library method that waits behind an if, and no "
                        + "corpus library ships that defect; Object.wait inside a library is "
                        + "woven by the same MONITOR_ENTRIES the jdk: pair goes through");
        unreached(DetectorType.EXPLICIT_GC,
                "no corpus library calls System.gc, and the detector is refused a pair in every "
                        + "lane anyway (DetectorCoverage)");
        unreached(DetectorType.DAEMON_THREAD_HYGIENE,
                "agent-fed since #731 through the woven Thread.start and Thread.setDaemon, and "
                        + "no agent pair has been written for it yet (#736)");
    }

    private static void unreached(DetectorType type, String reason) {
        UNREACHED.put(type, reason);
    }

    private LibraryReach() {
    }

    /** {@return the agent-fed detectors measured in both directions on library call sites} */
    static Set<DetectorType> reached() {
        Set<DetectorType> reached = EnumSet.noneOf(DetectorType.class);
        // Lane one: the body calls nothing but the library, and CorpusGates holds these to firing
        // on the documented-unsafe group while the documented-safe group stays clean.
        reached.addAll(CorpusGates.exercisedAgentDetectors());
        for (RecordingSubject loud : Corpus.subjectsFor(CorpusLane.AGENT_PAIRS)) {
            if (loud.expectation() != RecordingSubject.Expectation.MUST_FIRE
                    || !Corpus.wovenCallSiteIsInsideTheLibrary(loud)) {
                continue;
            }
            if (AgentRowPremise.twinOf(loud) != null) {
                reached.add(loud.detector());
            }
        }
        return reached;
    }

    /** {@return the agent-fed detectors no library reaches, with the reason for each} */
    static Map<DetectorType, String> unreached() {
        return Map.copyOf(UNREACHED);
    }

    /** {@return every detector the library classifies as fed by the agent} */
    static Set<DetectorType> agentFed() {
        Set<DetectorType> fed = EnumSet.noneOf(DetectorType.class);
        for (DetectorType type : DetectorType.values()) {
            if (DetectorFeeds.feedOf(type) == DetectorFeed.AGENT) {
                fed.add(type);
            }
        }
        return fed;
    }
}
