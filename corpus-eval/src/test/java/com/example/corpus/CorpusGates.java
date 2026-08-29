package com.example.corpus;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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

    /**
     * The fraction of documented-not-thread-safe subjects that must draw a finding.
     *
     * <p>Runs L, C21, C25 and C26 all detect every one of them, so the floor sits below the lowest
     * measurement rather than at it and leaves the group room to lose a few subjects to the
     * scheduler. It is a ratio and not a literal so that adding a subject raises the floor with
     * it: a documented-unsafe subject the corpus cannot detect on any platform belongs in an
     * issue, not in a denominator that quietly lowers the bar for everything else.
     */
    private static final double UNSAFE_DETECTION_FLOOR = 0.85;

    /**
     * The agent-fed detectors this corpus actually exercises, each of which must fire somewhere.
     *
     * <p>The lane exposes eighteen agent-fed detectors and two of them produce every finding in
     * the report. That is not a defect in the other sixteen: they model locks, latches, date
     * formats and builders, and a corpus whose whole test body is "share one instance and call
     * it" never writes those idioms down for them to see. Requiring all eighteen to fire would
     * fail on correct silence.
     *
     * <p>These two are different. Every finding this eval has recorded on any platform came from
     * one of them, so either going quiet across all twenty-two documented-unsafe subjects is a
     * regression rather than a schedule. Adding a subject that wakes a third detector breaks
     * nothing here; this set is a floor on what must speak, not a ceiling on what may.
     */
    private static final Set<DetectorType> EXERCISED_AGENT_DETECTORS =
            EnumSet.of(DetectorType.ATOMICITY_VIOLATIONS, DetectorType.SHARED_COLLECTIONS);

    private CorpusGates() {
    }

    static void check(List<CorpusRecorder.Finding> findings,
                      List<CorpusRecorder.Crash> crashes,
                      CorpusLane lane) {
        everySubjectIsExercised();
        everyFindingIsAttributed(findings, crashes);
        noFalsePositiveOnDocumentedThreadSafeCode(findings);
        everyReportingDetectorWasExposed(findings, lane);
        theAgentIsAttachedTheWayThisLaneRequires(lane);
        if (lane == CorpusLane.AGENT_ON) {
            theUnsafeGroupIsDetected(findings);
        } else {
            theAgentFedSetIsSilentWithoutTheAgent(findings);
        }
    }

    /**
     * The recording lane's gates, which are stronger than the unmodified lanes' on purpose.
     *
     * <p>There, whether one particular race is observed in one particular run is probabilistic,
     * so detection is asserted only at the group level. Here a detector's verdict is a function
     * of the {@code record*} calls the body made, so each subject's stated outcome is a
     * structural claim and is asserted per subject, in both directions. The false-positive rule
     * is the same absolute one either way.
     *
     * @param findings what the detectors reported
     * @param lane     the lane that produced them
     */
    static void checkRecordingLane(List<CorpusRecorder.Finding> findings, CorpusLane lane) {
        everyRecordingSubjectIsExercised();
        everyRecordingFindingIsAttributed(findings);
        theAgentIsAttachedTheWayThisLaneRequires(lane);
        everyRecordedDetectorIsExposed(lane);
        everyReportingDetectorWasExposed(findings, lane);
        everySubjectGotTheOutcomeItsRecordedCallsOblige(findings);
    }

    /** A recording test method without a row would be a subject with no stated expectation. */
    private static void everyRecordingSubjectIsExercised() {
        Set<String> exercised = Arrays.stream(CorpusRecordingLaneTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(AsyncTest.class))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> declared = Corpus.recordingSubjects().stream()
                .map(RecordingSubject::testMethod)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(declared, exercised,
                "every @AsyncTest method in the recording lane must have a RecordingSubject row "
                        + "and every row a method");
    }

    private static void everyRecordingFindingIsAttributed(List<CorpusRecorder.Finding> findings) {
        List<String> orphans = findings.stream()
                .map(CorpusRecorder.Finding::subject)
                .filter(subject -> Corpus.recordingByTestMethod(subject) == null)
                .distinct()
                .toList();
        assertTrue(orphans.isEmpty(), "findings attributed to no recording subject: " + orphans);
    }

    /**
     * A detector the lane records to must be exposed, or its row in the report is a lie.
     *
     * <p>This is the structural half the issue asks for. If a detector's feed classification
     * changes, or a subject is pointed at a detector nothing feeds, the report would print a
     * denominator for something that could never have spoken - which is the exact failure the
     * exposure table exists to prevent, reintroduced from the other side.
     *
     * @param lane the lane that ran
     */
    private static void everyRecordedDetectorIsExposed(CorpusLane lane) {
        List<String> unexposed = Corpus.recordedDetectors().stream()
                .filter(type -> !DetectorExposure.isExposed(type, lane))
                .map(Enum::name)
                .toList();

        assertTrue(unexposed.isEmpty(),
                "these detectors are recorded to by a subject in this lane but DetectorExposure "
                        + "says nothing here can feed them, so every rate the report prints for "
                        + "them is measured over a denominator that does not exist: " + unexposed);
    }

    /**
     * Each subject got the outcome its own recorded calls oblige, in both directions.
     *
     * <p>Reported together rather than one assertion per subject: a change to a detector's model
     * usually moves several rows at once, and seeing which pairs broke is the difference between
     * a diagnosis and a rerun.
     *
     * @param findings what the detectors reported
     */
    private static void everySubjectGotTheOutcomeItsRecordedCallsOblige(
            List<CorpusRecorder.Finding> findings) {
        List<String> wrong = new ArrayList<>();
        for (RecordingSubject subject : Corpus.recordingSubjects()) {
            String detectorClass = DetectorExposure.classOf(subject.detector());
            boolean fired = findings.stream()
                    .anyMatch(finding -> finding.subject().equals(subject.testMethod())
                            && finding.detector().equals(detectorClass));

            boolean shouldFire = subject.expectation() == RecordingSubject.Expectation.MUST_FIRE;
            if (fired == shouldFire) {
                continue;
            }
            wrong.add((shouldFire ? "SILENT but must fire: " : "FIRED but must stay silent: ")
                    + subject.testMethod() + " [" + detectorClass + "] - "
                    + subject.rationale()
                    + (fired ? "; it said: " + evidenceFor(findings, subject, detectorClass) : ""));
        }

        assertTrue(wrong.isEmpty(),
                "the recording lane's expectations follow from the calls each body makes, not "
                        + "from how the scheduler interleaved them, so every one of these is a "
                        + "change in what the detector concludes: " + String.join(" | ", wrong));
    }

    /** {@return what the detector said about {@code subject}, for a failure message} */
    private static String evidenceFor(List<CorpusRecorder.Finding> findings,
                                      RecordingSubject subject,
                                      String detectorClass) {
        return findings.stream()
                .filter(finding -> finding.subject().equals(subject.testMethod())
                        && finding.detector().equals(detectorClass))
                .map(finding -> finding.tier() + "/" + finding.severity() + " " + finding.message())
                .findFirst()
                .orElse("-");
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

    /**
     * The agent must be attached at JVM startup, not from inside the first test.
     *
     * <p>This is a measurement gate, not a style one. A self-attach happens partway through the
     * run and weaves only what loads after it, so which subjects the agent can see depends on
     * which test class Surefire runs first. That ordering differs between a developer machine and
     * a CI runner, and it moved this eval's detection from 20 of 20 to 6 of 20 with nothing else
     * changed: {@code -Dsurefire.runOrder=reversealphabetical} reproduced the CI result exactly,
     * down to the per-subject event counts. A launch flag removes the ordering from the
     * measurement, and this gate refuses to let the module drift back.
     *
     * @param lane which lane is running
     */
    private static void theAgentIsAttachedTheWayThisLaneRequires(CorpusLane lane) {
        boolean launched = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(argument -> argument.startsWith("-javaagent:")
                        && argument.contains("async-test-agent"));

        if (lane == CorpusLane.AGENT_ON) {
            assertTrue(launched,
                    "the agent-on lane must attach the agent with -javaagent at JVM startup. "
                            + "Self-attaching from the first @AsyncTest weaves only classes loaded "
                            + "after that point, which makes every number in this report depend on "
                            + "the order Surefire happens to run the test classes in");
        } else {
            assertFalse(launched,
                    "this lane must have nothing attached. In the control lane a -javaagent flag "
                            + "would make its silence meaningless; in the recording lane it would "
                            + "make every finding ambiguous between the agent's stream and the "
                            + "body's own recorded calls");
        }
    }

    /**
     * The true-positive side, gated rather than only reported.
     *
     * <p>This gate used to pass on one finding <em>or one crash</em> anywhere in the group, and
     * three of the documented-not-thread-safe subjects throw on most runs. The crash half alone
     * therefore satisfied it: both agent-fed detectors could have gone silent on every subject in
     * the corpus and this module would still have published its detection table green. The
     * headline number was reported, never checked.
     *
     * <p>It is checked here in two ways, neither of which pins a particular subject to a
     * particular run:
     *
     * <ul>
     *   <li>each of {@link #EXERCISED_AGENT_DETECTORS} must report on at least one
     *       documented-not-thread-safe subject. <em>Which</em> subjects a detector catches moves
     *       with the scheduler; whether it catches any of twenty-two does not, so a detector that
     *       has stopped working fails here on the first run rather than on the first reader.</li>
     *   <li>the group as a whole must reach {@link #UNSAFE_DETECTION_FLOOR} of its subjects, which
     *       catches the degradation that leaves each detector alive but firing far less often.</li>
     * </ul>
     *
     * <p>Crashes count towards neither any more. A corrupted subject that throws instead of
     * drawing a finding is a symptom the report prints per subject and the analysis document
     * already calls a symptom rather than a measurement; it is not evidence that a detector saw
     * anything.
     *
     * @param findings what the detectors reported in this lane
     */
    private static void theUnsafeGroupIsDetected(List<CorpusRecorder.Finding> findings) {
        Set<String> detected = findings.stream()
                .filter(finding -> contractOf(finding.subject()) == Contract.NOT_THREAD_SAFE)
                .map(CorpusRecorder.Finding::subject)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        long subjects = Corpus.count(Contract.NOT_THREAD_SAFE);
        long floor = (long) Math.floor(subjects * UNSAFE_DETECTION_FLOOR);

        assertTrue(detected.size() >= floor,
                detected.size() + " of the " + subjects + " documented-not-thread-safe subjects "
                        + "drew a finding, and this gate requires " + floor + ". Every keyed "
                        + "platform run detects all of them, so a number this far below that is a "
                        + "detector regression rather than scheduler variance. Detected: "
                        + detected);

        List<String> silent = EXERCISED_AGENT_DETECTORS.stream()
                .filter(type -> findings.stream().noneMatch(finding ->
                        contractOf(finding.subject()) == Contract.NOT_THREAD_SAFE
                                && DetectorExposure.typeOf(finding.detector())
                                        .filter(reported -> reported == type)
                                        .isPresent()))
                .map(Enum::name)
                .toList();

        assertTrue(silent.isEmpty(),
                "these detectors produce every finding this corpus has ever recorded, and said "
                        + "nothing about any of the " + subjects + " subjects whose own javadoc "
                        + "documents them as not thread-safe. That is the shape a detector that "
                        + "has stopped working takes, not the shape of an unlucky schedule: "
                        + silent);
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
