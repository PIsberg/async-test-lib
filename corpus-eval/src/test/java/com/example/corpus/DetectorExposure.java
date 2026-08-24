package com.example.corpus;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.DetectorFeeds;
import se.deversity.asynctest.diagnostics.DetectorTrust;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which detectors this run could have fed, which is the denominator every rate needs.
 *
 * <p>Counting findings without it produces a number nobody can read: "no false positive from
 * detector X" and "X never ran" are the same row. A detector is fed by one of three things
 * ({@link DetectorFeed}), and which of them this module supplies is fixed by how it is built:
 *
 * <ul>
 *   <li>{@link DetectorFeed#AGENT} - fed in the attached lane and starved in the other, because
 *       the woven field and collection streams are the agent's output;
 *   <li>{@link DetectorFeed#ZERO_CONFIG} - fed in both, because a {@code ThreadMXBean} scan and a
 *       thread dump need nothing from the test;
 *   <li>{@link DetectorFeed#RECORDING} - fed in neither. The corpus calls no {@code record*} or
 *       {@code register*} API by design: the whole point is that no line of the subject library
 *       and no line of the test cooperates with the detector. So 137 of the 142 have an exposure
 *       of zero here and this eval says nothing whatever about them.
 * </ul>
 *
 * <p>The classification itself is the library's, not this module's, and its own gate
 * ({@code DetectorFeedCoverageTest}) pins the agent-fed rows to the code the streams are wired
 * into. This class only decides what the lane supplies.
 */
final class DetectorExposure {

    private DetectorExposure() {
    }

    /** {@return whether {@code type} could receive any input at all in {@code lane}} */
    static boolean isExposed(DetectorType type, CorpusLane lane) {
        return switch (DetectorFeeds.feedOf(type)) {
            case ZERO_CONFIG -> true;
            case AGENT -> lane == CorpusLane.AGENT_ON;
            case RECORDING -> false;
        };
    }

    /** {@return the detectors {@code lane} can feed, agent-fed first, in declaration order} */
    static Set<DetectorType> exposed(CorpusLane lane) {
        Set<DetectorType> result = new LinkedHashSet<>();
        for (DetectorFeed feed : new DetectorFeed[] {DetectorFeed.AGENT, DetectorFeed.ZERO_CONFIG}) {
            for (DetectorType type : DetectorFeeds.fedBy(feed)) {
                if (isExposed(type, lane)) {
                    result.add(type);
                }
            }
        }
        return result;
    }

    /** {@return the detectors of {@code feed}} */
    static Set<DetectorType> fedBy(DetectorFeed feed) {
        return DetectorFeeds.fedBy(feed);
    }

    /** {@return the feed of {@code type}} */
    static DetectorFeed feedOf(DetectorType type) {
        return DetectorFeeds.feedOf(type);
    }

    /**
     * {@return the detector class the reporting name refers to, resolved through the trust table}
     *
     * @param detectorName the name a {@code Violation} carries
     */
    static Optional<DetectorType> typeOf(String detectorName) {
        return DetectorTrust.typeOfDetector(detectorName);
    }

    /** {@return the class name the trust table records for {@code type}} */
    static String classOf(DetectorType type) {
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (row.type() == type) {
                return row.detectorClass();
            }
        }
        throw new IllegalStateException(type + " has no DetectorTrust row");
    }

    /** {@return every detector the library ships, so a denominator is never guessed} */
    static Set<DetectorType> all() {
        return EnumSet.allOf(DetectorType.class);
    }
}
