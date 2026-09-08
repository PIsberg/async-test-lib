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
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

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

    /**
     * {@return the agent-fed detectors lane one holds to firing on the documented-unsafe group}
     *
     * <p>Exposed so that {@link DetectorCoverage} counts them as paired from the same set this
     * gate enforces. Restating the two names there would be a second copy of a fact that already
     * has an owner, and the copy is the one that goes stale.
     */
    static Set<DetectorType> exercisedAgentDetectors() {
        return EXERCISED_AGENT_DETECTORS;
    }

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
    static void checkPairLane(List<CorpusRecorder.Finding> findings,
                              CorpusLane lane,
                              Class<?> laneTest) {
        everyRecordingSubjectIsExercised(lane, laneTest);
        everyRecordingFindingIsAttributed(findings, lane);
        theAgentIsAttachedTheWayThisLaneRequires(lane);
        everyPairedDetectorIsExposed(lane);
        everyReportingDetectorWasExposed(findings, lane);
        everySubjectGotTheOutcomeItsRecordedCallsOblige(findings, lane);
        noCollateralFindingOnASilentRow(findings, lane);
        if (lane == CorpusLane.AGENT_PAIRS) {
            noAgentRowRecordedItsOwnFinding();
            return;
        }
        everyCorpusBackedVerdictResolvesToItsPair();
        everySilentRowReachesItsDetector();
    }


    /**
     * Refuses a MUST_STAY_SILENT row that never addresses the detector it names.
     *
     * <p>The per-subject outcome gate holds a silent row to saying nothing, and a row that never
     * calls the detector satisfies that for free. {@link SilentRowPremise} carries the reasoning
     * and the one row that was doing it.
     */
    /**
     * Refuses an agent-lane row that fed its own detector.
     *
     * <p>The mirror image of {@link #everySilentRowReachesItsDetector()}. That one refuses a
     * silence that came from making no call; this one refuses a finding that came from making
     * one. Both rows of an agent pair have to get their entire input from the substituted call
     * sites, or the pair measures the test rather than the agent. {@link AgentRowPremise} carries
     * the reasoning.
     */
    private static void noAgentRowRecordedItsOwnFinding() {
        List<String> recording = AgentRowPremise.linesThatRecord();
        List<String> dropped = AgentRowPremise.pairsWhoseSilentRowDropsACall();
        assertTrue(dropped.isEmpty(),
                "a silent row here is only evidence if it went through the same substituted call "
                        + "sites as the row it is paired with: " + dropped);
        assertTrue(recording.isEmpty(),
                "the agent-pair lane's whole claim is that the woven call sites feed these "
                        + "detectors with nothing instrumented, and a body that records is "
                        + "measuring the recording API instead: " + recording);
    }

    private static void everySilentRowReachesItsDetector() {
        List<String> broken = SilentRowPremise.rowsThatNeverReachTheirDetector();
        assertTrue(broken.isEmpty(),
                "a silent row is only evidence if the detector had something to be silent about, "
                        + "and these never gave it anything: " + broken);
    }

    /**
     * The other half of the cross-module VERDICT evidence seam.
     *
     * <p>{@code TrustTier.VERDICT} means a finding says the code is wrong, so the library only
     * grants it against a case that fires on a bug and a case that stays silent on the correct
     * twin. Its own gate resolves that evidence by reflection over its own test methods, which
     * cannot reach this module: this module depends on the library, so the library cannot depend
     * back. 58 detectors are classified VERDICT on the strength of pairs that live here, named
     * in {@code META-INF/async-test/verdict-evidence-corpus}.
     *
     * <p>A name in a file is not evidence. This resolves every line against the rows it names and
     * fails if one is missing, points at a different detector, or has drifted to the wrong
     * expectation. The pairs themselves are held to their outcomes every run by
     * {@link #everySubjectGotTheOutcomeItsRecordedCallsOblige}, so between the two the tier cannot
     * outlive the measurement that earned it.
     */
    private static void everyCorpusBackedVerdictResolvesToItsPair() {
        String resource = "/META-INF/async-test/verdict-evidence-corpus";
        String content;
        try (java.io.InputStream in = CorpusGates.class.getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " is not on the classpath, so the eight VERDICT "
                    + "tiers this module backs cannot be checked from either side");
            content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not read " + resource, e);
        }

        List<String> broken = new ArrayList<>();
        int lines = 0;
        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            lines++;
            int equals = line.indexOf('=');
            if (equals <= 0) {
                broken.add("malformed line: " + line);
                continue;
            }
            DetectorType detector = DetectorType.valueOf(line.substring(0, equals).strip());
            String[] ids = line.substring(equals + 1).split(",");
            if (ids.length != 2) {
                broken.add(detector + " must name two subjects, fire first, and names "
                        + ids.length);
                continue;
            }
            broken.addAll(problemsWith(detector, ids[0].strip(),
                    RecordingSubject.Expectation.MUST_FIRE));
            broken.addAll(problemsWith(detector, ids[1].strip(),
                    RecordingSubject.Expectation.MUST_STAY_SILENT));
        }

        assertTrue(lines > 0, resource + " parsed to no lines at all, so this gate passed by "
                + "reading nothing");
        assertTrue(broken.isEmpty(),
                "the library classifies these detectors VERDICT because this module measures both "
                        + "directions for them, and the evidence it names no longer resolves here. "
                        + "Either restore the rows or lower the tier: " + broken);
    }

    /** {@return what is wrong with the row {@code id}, as evidence for {@code detector}} */
    private static List<String> problemsWith(DetectorType detector,
                                             String id,
                                             RecordingSubject.Expectation expected) {
        RecordingSubject subject = Corpus.recordingByTestMethod(id);
        if (subject == null) {
            return List.of(detector + " names " + id + ", which is not a recording subject");
        }
        List<String> problems = new ArrayList<>();
        if (subject.detector() != detector) {
            problems.add(id + " is cited for " + detector + " but records to " + subject.detector());
        }
        if (subject.expectation() != expected) {
            problems.add(id + " is cited as the " + expected + " half for " + detector
                    + " but is declared " + subject.expectation());
        }
        return problems;
    }

    /** A recording test method without a row would be a subject with no stated expectation. */
    static void everyRecordingSubjectIsExercised(CorpusLane lane, Class<?> laneTest) {
        Set<String> exercised = Arrays.stream(laneTest.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(AsyncTest.class))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> declared = Corpus.subjectsFor(lane).stream()
                .map(RecordingSubject::testMethod)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(declared, exercised,
                "every @AsyncTest method in the recording lane must have a RecordingSubject row "
                        + "and every row a method");
    }

    static void everyRecordingFindingIsAttributed(List<CorpusRecorder.Finding> findings,
                                                          CorpusLane lane) {
        List<String> orphans = findings.stream()
                .map(CorpusRecorder.Finding::subject)
                .filter(subject -> Corpus.pairByTestMethod(lane, subject) == null)
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
    static void everyPairedDetectorIsExposed(CorpusLane lane) {
        List<String> unexposed = Corpus.pairedDetectors(lane).stream()
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
    static void everySubjectGotTheOutcomeItsRecordedCallsOblige(
            List<CorpusRecorder.Finding> findings, CorpusLane lane) {
        List<String> wrong = new ArrayList<>();
        for (RecordingSubject subject : Corpus.subjectsFor(lane)) {
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

    /**
     * A correct twin must draw no VERDICT-tier finding from any detector, not only from its own.
     *
     * <p>{@link #everySubjectGotTheOutcomeItsRecordedCallsOblige} matches a finding to the detector
     * the row names, which is the whole question for the firing half and half of it for the silent
     * one. A {@code MUST_STAY_SILENT} body is this module writing down that a use is correct, so a
     * VERDICT-tier finding from any other detector on that body is the library saying that same
     * code is wrong. Nothing looked for one, and {@link CorpusReport} filtered its per-subject
     * table the same way, so such a finding would not even have been printed.
     *
     * <p>How much collateral a row may draw depends on the lane, and the reasoning lives on
     * {@link CorpusLane#failsOnAnyCollateral()} because it is a statement about how each lane's
     * bodies are written rather than about this gate. In short: the recording lane fails on any
     * collateral finding at all, because its bodies contain nothing the row is not about; the
     * agent-pair lane fails only at VERDICT/HIGH or VERDICT/CRITICAL, the bar
     * {@link CorpusReport#isFalsePositive} uses, because {@link AgentRowPremise} obliges its
     * bodies to carry scaffolding the row never spoke for.
     *
     * <p>That asymmetry was measured rather than assumed, in both directions. The first version of
     * this gate was absolute everywhere, and it failed on
     * {@code agent_deadlock_noThreadBlockedOnAnother}, whose two nested monitors are held across a
     * {@code Thread.sleep} that {@code SleepInLockDetector} reports on. The sleep looked like
     * harness scaffolding until {@link AgentRowPremise} refused the row without it: a silent row
     * has to go through the same substituted call sites as the twin it is paired with, and the
     * twin sleeps. So that finding is a true observation about a body that only ever claimed to be
     * free of deadlock, and the absolute form was wrong for that lane rather than the row.
     *
     * <p>It was never wrong for the recording lane, and saying so uniformly cost that lane its
     * ratchet. Every one of its silent rows was already clean across the whole roster at every
     * tier when the uniform bar was chosen, and is still clean: the lane produces 117 findings for
     * its 117 must-fire rows and nothing else. For 119 rows the gate was asserting a bar the
     * corpus cleared by a margin nothing measured. It now asserts the margin.
     *
     * <p>One consequence is worth stating because it dates the paragraph above. When the uniform
     * bar was written, {@code SleepInLockDetector} sat at PROMPT, and "sub-VERDICT collateral is
     * printed rather than asserted" described the exception exactly. The 2026-09-08 promotion wave
     * moved that detector to VERDICT, so the one entry this gate tolerates is now VERDICT at
     * MEDIUM: it is the severity half of the bar holding it, not the tier half. A bar justified by
     * one half while resting on the other is one promotion away from being wrong, and a reader
     * should know which half is load-bearing.
     *
     * <p>Firing rows are out of scope at every tier. Their bodies are wrong on purpose, so a second
     * detector speaking is a second true positive: both latch detectors report the timed-out await
     * in {@code agent_countDownLatch_awaitTimedOut}, and both are right to.
     *
     * @param findings what the detectors reported
     * @param lane     the lane that produced them
     */
    static void noCollateralFindingOnASilentRow(List<CorpusRecorder.Finding> findings,
                                                CorpusLane lane) {
        List<String> collateral = new ArrayList<>();
        for (CorpusRecorder.Finding finding : collateralOnSilentRows(findings, lane)) {
            if (!lane.failsOnAnyCollateral() && !isTheLibrarysStrongestClaim(finding)) {
                continue;
            }
            collateral.add(finding.detector() + " reported " + finding.tier() + "/"
                    + finding.severity() + " on " + finding.subject() + ", whose row states only "
                    + "that " + DetectorExposure.classOf(
                            Corpus.pairByTestMethod(lane, finding.subject()).detector())
                    + " stays silent: " + finding.evidence());
        }

        assertTrue(collateral.isEmpty(),
                "these rows are the corpus's own statement that a use is correct, and another "
                        + "detector spoke about them anyway. " + barOf(lane) + " There is no "
                        + "reading under which both are right: either the finding is a false "
                        + "positive on code this module vouches for, or the row's rationale is "
                        + "wrong about what the body does: " + collateral);
    }

    /**
     * {@return whether {@code finding} is the library claiming the code is wrong, not asking}
     *
     * <p>The same pair of conditions {@link CorpusReport#isFalsePositive} uses on documented-safe
     * subjects, so that "false positive" means one thing in this module. Kept as a named predicate
     * because it is the agent-pair lane's whole bar, and an inlined tier-and-severity test reads as
     * an arbitrary threshold rather than as the claim it stands for.
     *
     * @param finding what a detector reported
     */
    private static boolean isTheLibrarysStrongestClaim(CorpusRecorder.Finding finding) {
        return finding.tier() == TrustTier.VERDICT
                && (finding.severity() == IssueSeverity.HIGH
                        || finding.severity() == IssueSeverity.CRITICAL);
    }

    /** {@return the sentence naming the bar {@code lane} applies, for a failure message} */
    private static String barOf(CorpusLane lane) {
        return lane.failsOnAnyCollateral()
                ? "In the " + lane.propertyValue() + " lane the bar is absolute: these bodies "
                        + "contain nothing their row is not about, so a finding from any other "
                        + "detector, at any tier, is a defect on one side or the other."
                : "In the " + lane.propertyValue() + " lane the bar is VERDICT at HIGH or "
                        + "CRITICAL, which is the library making its strongest claim.";
    }

    /**
     * {@return every finding on a silent row that came from a detector other than the row's own}
     *
     * <p>Shared with {@link CorpusReport} so that what the gate asserts and what the report prints
     * are the same set, differing only in the tier bar. A second copy of this loop would be a
     * second definition of "collateral", and the copy is the one that goes stale.
     *
     * @param findings what the detectors reported
     * @param lane     the lane that produced them
     */
    static List<CorpusRecorder.Finding> collateralOnSilentRows(
            List<CorpusRecorder.Finding> findings, CorpusLane lane) {
        List<CorpusRecorder.Finding> collateral = new ArrayList<>();
        for (RecordingSubject subject : Corpus.subjectsFor(lane)) {
            if (subject.expectation() != RecordingSubject.Expectation.MUST_STAY_SILENT) {
                continue;
            }
            String own = DetectorExposure.classOf(subject.detector());
            for (CorpusRecorder.Finding finding : findings) {
                if (finding.subject().equals(subject.testMethod())
                        && !finding.detector().equals(own)) {
                    collateral.add(finding);
                }
            }
        }
        return collateral;
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

    static void everyFindingIsAttributed(List<CorpusRecorder.Finding> findings,
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

    static void noFalsePositiveOnDocumentedThreadSafeCode(List<CorpusRecorder.Finding> findings) {
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
     * <p>{@code DetectorFeeds} claims 125 of the 146 detectors cannot say anything until the test
     * body records what it did. This module records nothing, so if one of them speaks the claim is
     * false and every denominator printed in the report is wrong. Reading the report's zeroes as
     * "looked and saw nothing" depends on this holding.
     */
    static void everyReportingDetectorWasExposed(List<CorpusRecorder.Finding> findings,
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
    static void theAgentIsAttachedTheWayThisLaneRequires(CorpusLane lane) {
        boolean launched = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(argument -> argument.startsWith("-javaagent:")
                        && argument.contains("async-test-agent"));

        if (lane == CorpusLane.AGENT_ON || lane == CorpusLane.AGENT_PAIRS) {
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
    static void theUnsafeGroupIsDetected(List<CorpusRecorder.Finding> findings) {
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
    static void theAgentFedSetIsSilentWithoutTheAgent(List<CorpusRecorder.Finding> findings) {
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
