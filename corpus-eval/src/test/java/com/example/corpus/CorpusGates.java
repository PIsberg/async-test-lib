package com.example.corpus;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What must hold every run, whatever the detectors happened to see.
 *
 * <p>Deliberately one-directional. The false-positive gate is absolute: a documented-thread-safe
 * class must never draw a VERDICT-tier HIGH or CRITICAL finding, because that tier is the
 * library's claim that the code is wrong. Detection is gated only at the group level, since
 * whether one particular race is observed in one particular run is probabilistic and a per-subject
 * assertion would be a flaky gate rather than a measurement.
 *
 * <p>Two gates are about the exposure table rather than the subjects. Nothing here records
 * anything by hand, so a recording-fed detector has no input in either lane and must stay silent;
 * and with the agent detached the agent-fed pair loses its input too. Both are properties of how
 * the module is built, not of how the scheduler behaved, so both can be asserted outright.
 */
final class CorpusGates {

    private CorpusGates() {
    }

    static void check(List<CorpusRecorder.Finding> findings,
                      List<CorpusRecorder.Crash> crashes,
                      CorpusLane lane) {
        everySubjectIsExercised();
        everyFindingIsAttributed(findings, crashes);
        noFalsePositiveOnDocumentedThreadSafeCode(findings);
        everyReportingDetectorWasExposed(findings, lane);
        if (lane == CorpusLane.AGENT_ON) {
            theUnsafeGroupIsDetected(findings, crashes);
        } else {
            theAgentFedSetIsSilentWithoutTheAgent(findings);
        }
    }

    /** A test method without a corpus row would be a subject with no documented contract. */
    private static void everySubjectIsExercised() {
        Set<String> exercised = Arrays.stream(CorpusEvalTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(AsyncTest.class))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> declared = Corpus.subjects().stream()
                .map(Subject::testMethod)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(declared, exercised,
                "every @AsyncTest method must have a Corpus row and every Corpus row a method");
    }

    private static void everyFindingIsAttributed(List<CorpusRecorder.Finding> findings,
                                                 List<CorpusRecorder.Crash> crashes) {
        List<String> orphans = findings.stream()
                .map(CorpusRecorder.Finding::subject)
                .filter(subject -> Corpus.byTestMethod(subject) == null)
                .distinct()
                .toList();
        assertTrue(orphans.isEmpty(), "findings attributed to no subject: " + orphans);

        List<String> orphanCrashes = crashes.stream()
                .map(CorpusRecorder.Crash::subject)
                .filter(subject -> Corpus.byTestMethod(subject) == null)
                .distinct()
                .toList();
        assertTrue(orphanCrashes.isEmpty(), "crashes attributed to no subject: " + orphanCrashes);
    }

    private static void noFalsePositiveOnDocumentedThreadSafeCode(List<CorpusRecorder.Finding> findings) {
        List<CorpusRecorder.Finding> falsePositives = findings.stream()
                .filter(finding -> {
                    Subject subject = Corpus.byTestMethod(finding.subject());
                    return subject != null && CorpusReport.isFalsePositive(finding, subject);
                })
                .toList();

        assertTrue(falsePositives.isEmpty(),
                "a VERDICT-tier HIGH/CRITICAL finding on code its own javadoc documents as "
                        + "thread-safe is a false positive at the library's strongest claim: "
                        + falsePositives);
    }

    /**
     * The exposure table's own gate, from the measured direction.
     *
     * <p>{@code DetectorFeeds} claims 137 of the 142 detectors cannot say anything until the test
     * body records what it did. This module records nothing, so if one of them speaks the claim is
     * false and every denominator printed in the report is wrong. Reading the report's zeroes as
     * "looked and saw nothing" depends on this holding.
     */
    private static void everyReportingDetectorWasExposed(List<CorpusRecorder.Finding> findings,
                                                         CorpusLane lane) {
        List<String> unexposed = findings.stream()
                .map(CorpusRecorder.Finding::detector)
                .distinct()
                .filter(detector -> DetectorExposure.typeOf(detector)
                        .map(type -> !DetectorExposure.isExposed(type, lane))
                        .orElse(true))
                .toList();

        assertTrue(unexposed.isEmpty(),
                "these detectors reported in the " + lane.propertyValue() + " lane although "
                        + "DetectorFeeds says nothing in this run can feed them, so either the "
                        + "feed classification or the exposure denominators in the report are "
                        + "wrong: " + unexposed);
    }

    private static void theUnsafeGroupIsDetected(List<CorpusRecorder.Finding> findings,
                                                 List<CorpusRecorder.Crash> crashes) {
        boolean anythingObserved = findings.stream()
                .anyMatch(finding -> contractOf(finding.subject()) == Contract.NOT_THREAD_SAFE)
                || crashes.stream()
                .anyMatch(crash -> contractOf(crash.subject()) == Contract.NOT_THREAD_SAFE);

        assertFalse(!anythingObserved,
                "not one of the " + Corpus.count(Contract.NOT_THREAD_SAFE)
                        + " documented-not-thread-safe subjects produced a finding or threw; "
                        + "the harness saw nothing, which means it stopped measuring");
    }

    /**
     * The control lane. With nothing attached the woven streams do not exist, so the two agent-fed
     * detectors have no input and must produce nothing at all - which is what makes the attached
     * lane's findings attributable to the agent rather than to the harness.
     */
    private static void theAgentFedSetIsSilentWithoutTheAgent(List<CorpusRecorder.Finding> findings) {
        List<String> spoke = findings.stream()
                .filter(finding -> DetectorExposure.typeOf(finding.detector())
                        .map(CorpusGates::isAgentFed)
                        .orElse(false))
                .map(finding -> finding.detector() + " on " + finding.subject())
                .distinct()
                .toList();

        assertTrue(spoke.isEmpty(),
                "the agent is not attached in this lane, so an agent-fed detector has no stream to "
                        + "read and cannot have seen anything; these did: " + spoke);
    }

    private static boolean isAgentFed(DetectorType type) {
        return DetectorExposure.feedOf(type) == DetectorFeed.AGENT;
    }

    private static Contract contractOf(String testMethod) {
        Subject subject = Corpus.byTestMethod(testMethod);
        return subject == null ? null : subject.contract();
    }
}
