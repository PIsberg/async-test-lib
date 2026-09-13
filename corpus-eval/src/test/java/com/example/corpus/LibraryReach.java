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
 * <p>Verified once by exclusion rather than assumed: with {@code excludes=com.google;com.fasterxml;
 * com.zaxxer} added to the agent-pair lane's agent options, every library MUST_FIRE row went
 * silent and no other row moved, so each of those findings came from the library's bytecode.
 */
final class LibraryReach {

    /** Agent-fed detectors no corpus library reaches, each with what stops it. */
    private static final Map<DetectorType, String> UNREACHED = new EnumMap<>(DetectorType.class);

    static {
        unreached(DetectorType.SIMPLE_DATE_FORMAT,
                "Jackson builds SimpleDateFormat instances in its date serializers and "
                        + "StdDateFormat, but formats and parses through a DateFormat reference, and the weaver substitutes a call "
                        + "only when its owner is SimpleDateFormat or a subtype. The one "
                        + "SimpleDateFormat-typed call, in netty's Version, is on a local");
        unreached(DetectorType.SHARED_DECIMAL_FORMAT,
                "the only woven NumberFormat.format call is in Spring's StopWatch.prettyPrint, on "
                        + "a NumberFormat it creates per call. Spring's NumberUtils takes a "
                        + "caller's format but calls parse, which is not woven, and "
                        + "commons-collections4's MapUtils parses with an instance of its own");
        unreached(DetectorType.SHARED_MATCHER,
                "every corpus library creates a Matcher per call from a shared Pattern, which is "
                        + "the correct shape, so there is a silent half and no library bug to pair "
                        + "it with");
        unreached(DetectorType.SHARED_FORMATTER,
                "no corpus library calls java.util.Formatter.format");
        unreached(DetectorType.LATCH_MISUSE,
                "no corpus library calls countDown on a latch the caller supplies; the three "
                        + "countDown calls in Guava and netty are on latches those classes own");
        unreached(DetectorType.EXPLICIT_GC,
                "no corpus library calls System.gc, and the detector is refused a pair in every "
                        + "lane anyway (DetectorCoverage)");
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
                    || loud.library().startsWith("jdk:")) {
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
