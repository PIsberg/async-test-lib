package com.example.corpus;

import se.deversity.asynctest.DetectorType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which detectors the corpus pairs, and why the rest are refused.
 *
 * <p>The analysis document has claimed twice now that its refusal lists are exhaustive, and both
 * times the way to check was to read the prose and subtract by hand. That is the kind of claim
 * that is true when written and quietly false a month later, which is the same failure the corpus
 * exists to catch in detectors. So the claim lives here instead, as data, and
 * {@code EveryDetectorIsPairedOrRefusedTest} holds it to both directions: nothing may be missing
 * from both lists, and nothing may sit on the refusal list once it has a pair.
 *
 * <p>The second direction is the one that earns its keep. A refusal is written once, when the
 * detector looks unreachable, and there is nothing in a normal workflow that ever revisits it -
 * so a stale refusal outlives the reason for it indefinitely. Here, pairing a refused detector
 * fails the build until its entry is deleted.
 */
final class DetectorCoverage {

    /**
     * Detectors with no pair, each with the reason.
     *
     * <p>The reason is what makes an entry reviewable. "Cannot be paired" is not a refusal, it is
     * a shrug; every line here names the specific thing that stops it, so that a change to the
     * detector makes the line visibly wrong rather than silently obsolete.
     */
    private static final Map<DetectorType, String> REFUSED = new EnumMap<>(DetectorType.class);

    static {
        // --- What a refusal here does and does not cost.
        //
        //     The library's own DetectorFiringContractTest already requires every detector to
        //     have a test asserting a positive finding, so "can it fire" is gated for all 146
        //     whether or not the corpus pairs them. What a corpus pair adds on top is the other
        //     direction: a case that goes through the same calls and must stay silent, which is
        //     the false-positive half and the one no unit test tends to write.
        //
        //     So every entry below is a statement about that half specifically, not about the
        //     detector being untested. For the first group the silent half provably cannot exist;
        //     for the second it exists but cannot be asserted without the assertion depending on
        //     a clock or a core count.

        // --- Every recorded event is a finding, so the silent half would be a row that made no
        //     call. That is the shape #410 removed from this lane, and adding it back for the
        //     sake of a count would be adding back the exact defect the lane was built to fix.

        refuse(DetectorType.EXPLICIT_GC,
                "AgentGcHooks records every System.gc() and analyze() turns every event into a "
                        + "violation, deliberately and with no lock guard. Its own javadoc says "
                        + "System.gc() has no innocent twin");
        refuse(DetectorType.VIRTUAL_THREAD_PINNING,
                "every recorded pinning event is a finding, and the platform-thread variant "
                        + "records nothing at all, so the twin would make no call");
        refuse(DetectorType.THREAD_POOL_DEADLOCK,
                "any nestedSubmissionCount above zero fires, whatever the pool size, so no "
                        + "recorded nesting is correct");
        refuse(DetectorType.THIS_ESCAPE,
                "reports every instance with a non-empty escape set; a correct constructor makes "
                        + "no recordable call");
        refuse(DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE,
                "recordObtrude is the only record method and every entry is a violation");
        refuse(DetectorType.DEPRECATED_THREAD_API,
                "recordApiUse is the only record method and every entry is a violation");

        // --- The outcome is not a function of the recorded calls. A row whose expectation a GC
        //     pause or a core count can flip is a flaky gate, and this lane's whole claim is that
        //     its expectations are structural.

        refuse(DetectorType.FALSE_SHARING,
                "an experimental flag, a 100-access threshold, and two fields' accessing-thread "
                        + "sets having to differ");
        refuse(DetectorType.MEMORY_ORDERING,
                "a write and a read landing adjacent in a concurrently appended log, from "
                        + "different threads");
        refuse(DetectorType.THREAD_STARVATION,
                "elapsed nanoTime against a 1000 ms threshold");
        refuse(DetectorType.LOCK_DOWNGRADE,
                "the structural branch is deferred to LockUpgradeDeadlockDetector by the "
                        + "registry; what is left needs a cross-thread gap");
        refuse(DetectorType.PLATFORM_THREAD_PER_TASK,
                "a probe task against a 200 ms deadline");
        refuse(DetectorType.VIRTUAL_THREAD_CPU_BOUND,
                "a measured segment against a 50 ms threshold");
        refuse(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION,
                "concurrently blocked threads against availableProcessors, so it fires on small "
                        + "runners and not on large ones");
        refuse(DetectorType.LIVELOCKS,
                "every finding is a threshold over sampled thread state, including a CPU time "
                        + "that must be bit-identical between first and last sample; and a busy "
                        + "retry loop is deliberately not a finding, so the obvious firing row is "
                        + "guaranteed silent. Inert entirely under the default useVirtualThreads");


    }

    private static void refuse(DetectorType type, String reason) {
        REFUSED.put(type, reason);
    }

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
        // Lane one pairs these two over 82 unmodified subjects - fires on documented-unsafe,
        // silent on all 60 documented-safe - which is a stronger measurement than a two-row pair,
        // not a weaker one. CorpusGates owns the set so the two cannot drift apart.
        paired.addAll(CorpusGates.exercisedAgentDetectors());
        return paired;
    }

    /** {@return the detectors deliberately left unpaired, with the reason for each} */
    static Map<DetectorType, String> refused() {
        return Map.copyOf(REFUSED);
    }
}
