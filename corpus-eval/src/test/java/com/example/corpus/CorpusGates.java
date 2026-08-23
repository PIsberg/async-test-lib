package com.example.corpus;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import se.deversity.asynctest.AsyncTest;

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
 */
final class CorpusGates {

    private CorpusGates() {
    }

    static void check(List<CorpusRecorder.Finding> findings, List<CorpusRecorder.Crash> crashes) {
        everySubjectIsExercised();
        everyFindingIsAttributed(findings, crashes);
        noFalsePositiveOnDocumentedThreadSafeCode(findings);
        theUnsafeGroupIsDetected(findings, crashes);
    }

    /** A test method without a corpus row would be a subject with no documented contract. */
    private static void everySubjectIsExercised() {
        Set<String> exercised = java.util.Arrays.stream(CorpusEvalTest.class.getDeclaredMethods())
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

    private static Contract contractOf(String testMethod) {
        Subject subject = Corpus.byTestMethod(testMethod);
        return subject == null ? null : subject.contract();
    }
}
