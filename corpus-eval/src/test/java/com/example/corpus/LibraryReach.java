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
        unreached(DetectorType.ABA_PROBLEM,
                "agent-fed since #817 through the woven AtomicReference calls; a firing library "
                        + "row needs a library that pushes a popped node back onto a lock-free "
                        + "stack, and no corpus library ships that defect, so its pair calls the "
                        + "JDK type from the test file");
        unreached(DetectorType.SYNCHRONIZED_NON_FINAL,
                "agent-fed since #793 through the woven monitor entry of synchronized "
                        + "(owner.field); a firing library row needs a library that reassigns a "
                        + "field it synchronizes on, and no corpus library ships that defect, so "
                        + "its pair synchronizes in the test file");
        unreached(DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT,
                "agent-fed through the woven Map.get, containsKey, put and remove; a firing "
                        + "library row needs a documented-unsafe library method that checks a "
                        + "concurrent map and then acts on it, and no corpus library ships that "
                        + "defect, so its pair calls the JDK map from the test file");
        unreached(DetectorType.EXECUTOR_SHUTDOWN,
                "agent-fed through the woven Executors factories and submit; a firing library "
                        + "row needs a documented-unsafe library method that creates a pool "
                        + "through Executors and leaves it running, and no corpus library ships "
                        + "that defect, so its pair creates the pool in the test file");
        unreached(DetectorType.FUTURE_IGNORED,
                "agent-fed through the POP after a woven submit; a firing library row needs a "
                        + "library method that drops the future of a task that fails, and no "
                        + "corpus library ships that defect, so its pair submits from the test "
                        + "file");
        unreached(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS,
                "agent-fed through the POP after a woven supplyAsync or runAsync; a firing "
                        + "library row needs a library method that drops a failing async root, "
                        + "and no corpus library ships that defect");
        unreached(DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK,
                "agent-fed through the woven stage wrapper; a firing library row needs a "
                        + "library callback that blocks on a pending future, and no corpus library "
                        + "ships that defect");
        unreached(DetectorType.CF_COMMON_POOL_BLOCKING,
                "agent-fed through the woven supplyAsync and the blocking calls inside it; a "
                        + "firing library row needs a library task that blocks on the common "
                        + "pool, and no corpus library ships that defect");
        unreached(DetectorType.INTERRUPT_SWALLOWING,
                "agent-fed through the handler weaver; a firing library row needs a "
                        + "documented-unsafe library method whose InterruptedException handler "
                        + "swallows the interrupt on a path a subject runs, and no corpus library "
                        + "ships that defect");
        unreached(DetectorType.INTERRUPT_MISHANDLING,
                "agent-fed through the same handler weaver, with the same reason");
        unreached(DetectorType.SYNCHRONIZED_COLLECTION_ITERATION,
                "agent-fed through the woven iterator(), stream() and map views; a firing library "
                        + "row needs a documented-unsafe library method that iterates a "
                        + "synchronized wrapper unguarded, and no corpus library ships that defect");
        unreached(DetectorType.SYSTEM_PROPERTY_MUTATION,
                "agent-fed through the woven System.setProperty and the JVM-default setters; a "
                        + "firing library row needs a library method that writes one from several "
                        + "threads unguarded, and no corpus library ships that defect");
        unreached(DetectorType.PROCESS_STREAM_STARVATION,
                "agent-fed through the woven Process.waitFor and pipe getters; a firing library "
                        + "row needs a library method that starts a child and waits before "
                        + "draining it, and no corpus library starts processes");
        unreached(DetectorType.JDBC_CONNECTION_SHARED,
                "agent-fed since #992 through calls on a JPA EntityManager or Hibernate Session, "
                        + "matched by name; no corpus library calls one, and the JDBC types it also "
                        + "covers are recording-fed, so its recording-lane pair stands");
        unreached(DetectorType.THREAD_LOCAL_LEAKS,
                "agent-fed since #995 through ThreadLocal calls inside the pooled tasks a woven "
                        + "submit hands over; a firing library row needs a library that leaves "
                        + "per-request state on a pool thread for the next task to read, and no "
                        + "corpus library ships that defect");
        unreached(DetectorType.MDC_CONTEXT_LEAK,
                "agent-fed since #995 through the MDC snapshots around the pooled tasks a woven "
                        + "submit hands over; no corpus library writes to the MDC");
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
