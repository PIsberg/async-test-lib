package com.example.corpus;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
     * <p>The lane exposes eighteen agent-fed detectors and seven of them produce every finding in
     * the report. That is not a defect in the other eleven: they model locks, latches, queues,
     * calendars and digests, and a corpus whose whole test body is "share one instance and call
     * it" never writes those idioms down for them to see. Requiring all eighteen to fire would
     * fail on correct silence.
     *
     * <p>These two are different from the other five as well. Both fire on library subjects,
     * whose woven call sits inside the library's own bytecode, and {@link LibraryReach} counts this
     * set as reach through a library; either going quiet across the whole documented-unsafe group
     * is a regression rather than a schedule. The five shared-instance detectors the sixth wave woke
     * ({@code StringBuilderDetector}, {@code SimpleDateFormatDetector}, {@code SharedMatcherDetector},
     * {@code SharedFormatterDetector}, {@code SharedDecimalFormatDetector}) fire on JDK calls
     * written in {@code CorpusEvalTest} itself, so adding them here would count a test-file call
     * as library reach. Adding a subject that wakes another detector breaks nothing here; this set
     * is a floor on what must speak, not a ceiling on what may.
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
    /**
     * Holds the library rows to going silent when their libraries are not woven (#544).
     *
     * <p>A library pair claims its finding came from a JDK call inside the library. In this lane
     * the agent is attached with every library package on its exclude list, so those calls are
     * no longer substituted and nothing in a firing body should reach the detector. A row that
     * still fires is reaching it from somewhere else - most likely a woven JDK call someone wrote
     * into the test body - and its pair has stopped measuring what {@link LibraryReach} counts it
     * for.
     *
     * <p>Two premises are checked first, because either would make the silence vacuous: every
     * library row's package must actually be on the exclude list (a row from a library added
     * later, without updating the pom, would fire here for the right reason and be reported for
     * the wrong one), and every library row must have run its full N x M.
     *
     * @param findings            what the lane reported
     * @param laneTest            the lane's test class, whose methods the rows must name
     * @param executionsPerRow    body executions each row owes
     */
    static void checkLibraryExclusionLane(List<CorpusRecorder.Finding> findings,
                                          Class<?> laneTest,
                                          int executionsPerRow) {
        CorpusLane lane = CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED;
        theAgentIsAttachedTheWayThisLaneRequires(lane);
        List<RecordingSubject> rows = Corpus.subjectsFor(lane);
        Set<String> declaredMethods = Arrays.stream(laneTest.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(AsyncTest.class))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
        checkLibraryExclusionLane(findings, declaredMethods, executionsPerRow, rows,
                excludedPrefixes(), CorpusRecorder.bodyExecutions());
    }

    static void checkLibraryExclusionLane(List<CorpusRecorder.Finding> findings,
                                          Set<String> declaredMethods,
                                          int executionsPerRow,
                                          List<RecordingSubject> rows,
                                          List<String> excludedPrefixes,
                                          long bodyExecutions) {
        assertFalse(rows.isEmpty(), "the library-exclusion lane found no library rows to run");

        List<String> missing = rows.stream().map(RecordingSubject::testMethod)
                .filter(name -> !declaredMethods.contains(name)).toList();
        assertTrue(missing.isEmpty(), "library rows with no @AsyncTest method: " + missing);

        List<String> notExcluded = rows.stream()
                .filter(row -> excludedPrefixes.stream().noneMatch(prefix -> row.className().startsWith(prefix)))
                .map(row -> row.testMethod() + " (" + row.className() + ")")
                .toList();
        assertTrue(notExcluded.isEmpty(),
                "these library rows name a class the agent's excludes= list does not cover, so "
                        + "their library is still woven in this lane and silence proves nothing. "
                        + "Add the package to the agent-pairs-library-excluded execution in "
                        + "corpus-eval/pom.xml. Excluded: " + excludedPrefixes + "; uncovered: " + notExcluded);

        assertEquals((long) rows.size() * executionsPerRow, bodyExecutions,
                "every library row must run its full " + executionsPerRow + " executions here, or "
                        + "a silent row may be a row that did not run");

        List<String> stillFiring = new ArrayList<>();
        for (RecordingSubject row : rows) {
            String detectorClass = DetectorExposure.classOf(row.detector());
            boolean fired = findings.stream().anyMatch(finding ->
                    finding.subject().equals(row.testMethod())
                            && finding.detector().equals(detectorClass));
            if (fired) {
                stillFiring.add(row.testMethod() + " [" + detectorClass + ", "
                        + row.expectation() + "]: " + evidenceFor(findings, row, detectorClass));
            }
        }
        assertTrue(stillFiring.isEmpty(),
                "with Guava, Jackson and the other corpus libraries excluded from weaving, these "
                        + "library rows still reached their detector, so the finding did not come "
                        + "from the library's bytecode. Look for a woven JDK call in the body "
                        + "itself: " + String.join(" | ", stillFiring));
    }

    /** {@return the package prefixes the running JVM's -javaagent excludes= option names} */
    private static List<String> excludedPrefixes() {
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            int options = argument.startsWith("-javaagent:") ? argument.indexOf('=') : -1;
            if (options < 0 || !argument.contains("async-test-agent")) {
                continue;
            }
            List<String> prefixes = new ArrayList<>();
            boolean inExcludes = false;
            for (String token : argument.substring(options + 1).split("[,;]")) {
                int equals = token.indexOf('=');
                if (equals >= 0) {
                    inExcludes = token.substring(0, equals).trim().equalsIgnoreCase("excludes");
                    token = token.substring(equals + 1);
                    if (!inExcludes) {
                        continue;
                    }
                }
                if (inExcludes && !token.isBlank()) {
                    prefixes.add(token.trim());
                }
            }
            return prefixes;
        }
        return List.of();
    }

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
     * Refuses a run where the silent deadlock row ran on an already deadlocked JVM.
     */
    static void theDeadlockRowsRanInOrder(boolean silentRowRanOnCleanJvm, boolean deadlockStarted) {
        assertTrue(silentRowRanOnCleanJvm,
                "the silent deadlock row has to run before the row that deadlocks two threads "
                        + "permanently, or its silence is measuring the wrong JVM. It observed "
                        + "DEADLOCK_STARTED=" + deadlockStarted + " when it ran");
    }

    /**
     * Refuses a run where the pooled connection premise did not hold.
     */
    static void thePooledRowsPremiseHeld(int physicalConnections, int threadsThatUsedThePool) {
        assertEquals(1, physicalConnections,
                "the pool is sized to one so that every thread gets the same physical connection; "
                        + "with more than one, the silent row proves nothing about reuse across "
                        + "threads. Saw " + physicalConnections + " distinct connections");
        assertTrue(threadsThatUsedThePool > 1,
                "that one connection has to reach more than one thread, or the detector "
                        + "short-circuits before it reaches the rule under test and the silence "
                        + "is not evidence. Saw " + threadsThatUsedThePool + " thread(s)");
    }

    /**
     * Refuses a run where notifyAll outside a monitor did not throw IllegalMonitorStateException.
     */
    static void theIllegalNotifyReallyThrew(String outcome) {
        assertEquals("IllegalMonitorStateException", outcome,
                "the loud notify row claims the monitor is not held, and notifyAll outside a "
                        + "monitor must throw IllegalMonitorStateException. The JVM said: "
                        + outcome);
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
    static void noAgentRowRecordedItsOwnFinding() {
        noAgentRowRecordedItsOwnFinding(AgentRowPremise.read(), Corpus.subjectsFor(CorpusLane.AGENT_PAIRS));
    }

    static void noAgentRowRecordedItsOwnFinding(String source, List<RecordingSubject> subjects) {
        List<String> recording = AgentRowPremise.linesThatRecord(source);
        List<String> dropped = AgentRowPremise.pairsWhoseSilentRowDropsACall(source, subjects);
        assertTrue(dropped.isEmpty(),
                "a silent row here is only evidence if it went through the same substituted call "
                        + "sites as the row it is paired with: " + dropped);
        assertTrue(recording.isEmpty(),
                "the agent-pair lane's whole claim is that the woven call sites feed these "
                        + "detectors with nothing instrumented, and a body that records is "
                        + "measuring the recording API instead: " + recording);
    }

    static void everySilentRowReachesItsDetector() {
        everySilentRowReachesItsDetector(SilentRowPremise.read(), Corpus.recordingSubjects());
    }

    static void everySilentRowReachesItsDetector(String source, List<RecordingSubject> subjects) {
        List<String> broken = SilentRowPremise.rowsThatNeverReachTheirDetector(source, subjects);
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
     * back. 59 detectors are classified VERDICT on the strength of pairs that live here, named
     * in {@code META-INF/async-test/verdict-evidence-corpus}.
     *
     * <p>A name in a file is not evidence. This resolves every line against the rows it names and
     * fails if one is missing, points at a different detector, or has drifted to the wrong
     * expectation. The pairs themselves are held to their outcomes every run by
     * {@link #everySubjectGotTheOutcomeItsRecordedCallsOblige}, so between the two the tier cannot
     * outlive the measurement that earned it.
     */
    static void everyCorpusBackedVerdictResolvesToItsPair() {
        String resource = "/META-INF/async-test/verdict-evidence-corpus";
        String content;
        try (java.io.InputStream in = CorpusGates.class.getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " is not on the classpath, so the VERDICT tiers "
                    + "this module backs cannot be checked from either side");
            content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not read " + resource, e);
        }
        everyCorpusBackedVerdictResolvesToItsPair(content, Corpus::recordingByTestMethod);
    }

    static void everyCorpusBackedVerdictResolvesToItsPair(String content,
                                                         java.util.function.Function<String, RecordingSubject> resolver) {
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
            DetectorType detector;
            try {
                detector = DetectorType.valueOf(line.substring(0, equals).strip());
            } catch (IllegalArgumentException e) {
                broken.add("unknown detector: " + line.substring(0, equals).strip());
                continue;
            }
            String[] ids = line.substring(equals + 1).split(",");
            if (ids.length != 2) {
                broken.add(detector + " must name two subjects, fire first, and names "
                        + ids.length);
                continue;
            }
            broken.addAll(problemsWith(detector, ids[0].strip(),
                    RecordingSubject.Expectation.MUST_FIRE, resolver));
            broken.addAll(problemsWith(detector, ids[1].strip(),
                    RecordingSubject.Expectation.MUST_STAY_SILENT, resolver));
        }

        assertTrue(lines > 0, "verdict evidence parsed to no lines at all, so this gate passed by "
                + "reading nothing");
        assertTrue(broken.isEmpty(),
                "the library classifies these detectors VERDICT because this module measures both "
                        + "directions for them, and the evidence it names no longer resolves here. "
                        + "Either restore the rows or lower the tier: " + broken);
    }

    /** {@return what is wrong with the row {@code id}, as evidence for {@code detector}} */
    private static List<String> problemsWith(DetectorType detector,
                                             String id,
                                             RecordingSubject.Expectation expected,
                                             java.util.function.Function<String, RecordingSubject> resolver) {
        RecordingSubject subject = resolver.apply(id);
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
        Set<String> declared = Corpus.subjectsFor(lane).stream()
                .map(RecordingSubject::testMethod)
                .collect(Collectors.toUnmodifiableSet());
        everyRecordingSubjectIsExercised(laneTest, declared);
    }

    static void everyRecordingSubjectIsExercised(Class<?> laneTest, Set<String> declared) {
        Set<String> exercised = Arrays.stream(laneTest.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(AsyncTest.class))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(declared, exercised,
                "every @AsyncTest method in the recording lane must have a RecordingSubject row "
                        + "and every row a method");
    }

    static void everyRecordingFindingIsAttributed(List<CorpusRecorder.Finding> findings,
                                                  CorpusLane lane) {
        everyRecordingFindingIsAttributed(findings, subject -> Corpus.pairByTestMethod(lane, subject));
    }

    static void everyRecordingFindingIsAttributed(List<CorpusRecorder.Finding> findings,
                                                  java.util.function.Function<String, RecordingSubject> resolver) {
        List<String> orphans = findings.stream()
                .map(CorpusRecorder.Finding::subject)
                .filter(subject -> resolver.apply(subject) == null)
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
        everyPairedDetectorIsExposed(lane, Corpus.pairedDetectors(lane));
    }

    static void everyPairedDetectorIsExposed(CorpusLane lane, Set<DetectorType> paired) {
        List<String> unexposed = paired.stream()
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
        everySubjectGotTheOutcomeItsRecordedCallsOblige(findings, lane, Corpus.subjectsFor(lane));
    }

    static void everySubjectGotTheOutcomeItsRecordedCallsOblige(
            List<CorpusRecorder.Finding> findings, CorpusLane lane, List<RecordingSubject> subjects) {
        List<String> wrong = new ArrayList<>();
        for (RecordingSubject subject : subjects) {
            String detectorClass = DetectorExposure.classOf(subject.detector());
            List<CorpusRecorder.Finding> matches = findings.stream()
                    .filter(finding -> finding.subject().equals(subject.testMethod())
                            && finding.detector().equals(detectorClass))
                    .toList();
            boolean fired = !matches.isEmpty();

            boolean shouldFire = subject.expectation() == RecordingSubject.Expectation.MUST_FIRE;
            if (fired != shouldFire) {
                wrong.add((shouldFire ? "SILENT but must fire: " : "FIRED but must stay silent: ")
                        + subject.testMethod() + " [" + detectorClass + "] - "
                        + subject.rationale()
                        + (fired ? "; it said: " + evidenceFor(findings, subject, detectorClass) : ""));
                continue;
            }
            if (shouldFire) {
                boolean validDiagnostics = matches.stream().anyMatch(f ->
                        f.severity() != null && f.message() != null && !f.message().isBlank()
                                && f.evidence() != null && !f.evidence().isBlank());
                if (!validDiagnostics) {
                    wrong.add("FIRED with invalid diagnostics (null severity, blank message, or blank evidence): "
                            + subject.testMethod() + " [" + detectorClass + "] - "
                            + subject.rationale());
                }
                IssueSeverity expected = subject.expectedSeverity();
                if (expected != null) {
                    boolean severityMatches = matches.stream().anyMatch(f -> f.severity() == expected);
                    if (!severityMatches) {
                        List<IssueSeverity> actual = matches.stream().map(CorpusRecorder.Finding::severity).toList();
                        wrong.add("FIRED with unexpected severity: expected " + expected + " but got " + actual
                                + " on " + subject.testMethod() + " [" + detectorClass + "]");
                    }
                }
            }
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
    static void everySubjectIsExercised() {
        everySubjectIsExercised(CorpusEvalTest.class, Corpus.subjects().stream()
                .map(Subject::testMethod)
                .collect(Collectors.toUnmodifiableSet()));
    }

    static void everySubjectIsExercised(Class<?> testClass, Set<String> declared) {
        Set<String> exercised = Arrays.stream(testClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(AsyncTest.class))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(declared, exercised,
                "every @AsyncTest method must have a Corpus row and every Corpus row a method");
    }

    static void everyFindingIsAttributed(List<CorpusRecorder.Finding> findings,
                                         List<CorpusRecorder.Crash> crashes) {
        everyFindingIsAttributed(findings, crashes, Corpus::byTestMethod);
    }

    static void everyFindingIsAttributed(List<CorpusRecorder.Finding> findings,
                                         List<CorpusRecorder.Crash> crashes,
                                         java.util.function.Function<String, Subject> resolver) {
        List<String> orphans = findings.stream()
                .map(CorpusRecorder.Finding::subject)
                .filter(subject -> resolver.apply(subject) == null)
                .distinct()
                .toList();
        assertTrue(orphans.isEmpty(), "findings attributed to no subject: " + orphans);

        List<String> orphanCrashes = crashes.stream()
                .map(CorpusRecorder.Crash::subject)
                .filter(subject -> resolver.apply(subject) == null)
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
        everyReportingDetectorWasExposed(findings, lane, type -> DetectorExposure.isExposed(type, lane));
    }

    static void everyReportingDetectorWasExposed(List<CorpusRecorder.Finding> findings,
                                                 CorpusLane lane,
                                                 java.util.function.Predicate<DetectorType> isExposed) {
        List<String> unexposed = findings.stream()
                .map(CorpusRecorder.Finding::detector)
                .distinct()
                .filter(detector -> DetectorExposure.typeOf(detector)
                        .map(type -> !isExposed.test(type))
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

        if (lane.attachesTheAgent()) {
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
     *       with the scheduler; whether it catches any of the group does not, so a detector that
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

    /**
     * How many events may still be published in the quiet window after the last subject.
     *
     * <p>Surefire's forked JVM runs its own threads, {@code org.apache.maven.surefire}, which the
     * agent wove like any other class until #561. They moved the counter by 20 to 30 events per
     * 250 ms locally and on three green CI legs, by 271 on a JDK 25 CI run before the gate could
     * say why, and by 261 on a JDK 26 CI run where its thread sampler found
     * {@code surefire-forkedjvm-command-thread} in {@code Channels$3.readImpl} as the only runnable
     * thread in woven code, 100 samples of 100.
     * Lane one now excludes that package from weaving, and the local window reads 0.
     *
     * <p>A subject that leaves a task running is a different order of magnitude: the TimedSemaphore
     * timer this exists for published about 1,100 events per later subject, and 430 in a 250 ms
     * window on JDK 26. With Surefire excluded the window read 0 events on every measurement taken:
     * locally on JDK 26, and on the Java 21, 25 and 26 CI legs of PR #581 (run 34876816963). The
     * allowance is 20, above that floor for harness noise nobody has seen yet and more than
     * twenty times below the smallest leak this gate has caught.
     */
    static final long QUIET_WINDOW_ALLOWANCE = 20;

    /**
     * Nothing a subject started may keep publishing once the subjects are done.
     *
     * <p>Events are attributed to a subject by the telemetry counter's movement while it runs, and
     * subjects run one after another. A background thread a subject leaves running, a periodic
     * timer, a scheduler, an executor that never stops, therefore adds its own events to every
     * subject that runs after it, and nothing else notices: the sixth wave's {@code TimedSemaphore}
     * timer raised the median event count of the 52 older subjects that ran after it by 1,094 while
     * the 30 that ran before it moved by 60, and every gate stayed green. The leak is still running
     * when the last subject ends, which is where this looks for it.
     *
     * @param publishedAtStart the telemetry counter when the window opened
     * @param publishedAtEnd   the counter when it closed
     * @param windowMillis     how long the window was held open
     */
    static void nothingPublishesAfterTheLastSubject(long publishedAtStart, long publishedAtEnd,
                                                    long windowMillis) {
        nothingPublishesAfterTheLastSubject(publishedAtStart, publishedAtEnd, windowMillis, "");
    }

    /**
     * The same gate, carrying what was running when it tripped.
     *
     * <p>A count on its own says that something kept publishing, not what. The first CI failure of
     * this gate (JDK 25, 271 events against 100) could not be told apart from Surefire's own woven
     * stream flusher having more output to move, which is exactly the question the failure message
     * has to answer for whoever reads it next.
     *
     * @param publishedAtStart the telemetry counter when the window opened
     * @param publishedAtEnd   the counter when it closed
     * @param windowMillis     how long the window was held open
     * @param whoWasRunning    what {@link #threadsInsideNonPlatformCode} saw, or empty
     */
    static void nothingPublishesAfterTheLastSubject(long publishedAtStart, long publishedAtEnd,
                                                    long windowMillis, String whoWasRunning) {
        long published = publishedAtEnd - publishedAtStart;
        assertTrue(published <= QUIET_WINDOW_ALLOWANCE,
                published + " events were published in the " + windowMillis + " ms after the last "
                        + "subject finished, and at most " + QUIET_WINDOW_ALLOWANCE + " may be. A "
                        + "subject has left something running - a timer, a scheduler, an executor - "
                        + "whose woven accesses land in the event count of every subject that runs "
                        + "after it. Stop it in an @AfterEach."
                        + (whoWasRunning.isEmpty() ? ""
                                : " Runnable threads seen outside platform code right after the "
                                        + "window, with sample counts: " + whoWasRunning));
    }

    /**
     * {@return which runnable threads are executing outside the JDK and JUnit, sampled}
     *
     * <p>Called only once the quiet window has already failed, so it costs nothing on a green run.
     * Each sample takes every thread's stack and keeps the first frame that is not platform code;
     * a leaked timer shows up as its own thread inside the library that started it, and the
     * harness as Surefire's.
     *
     * @param samples        how many snapshots to take
     * @param intervalMillis the pause between snapshots
     * @throws InterruptedException if the sampling thread is interrupted
     */
    static String threadsInsideNonPlatformCode(int samples, long intervalMillis)
            throws InterruptedException {
        Map<String, Integer> seen = new TreeMap<>();
        for (int sample = 0; sample < samples; sample++) {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (thread == Thread.currentThread() || thread.getState() != Thread.State.RUNNABLE) {
                    continue;
                }
                for (StackTraceElement frame : entry.getValue()) {
                    if (!isPlatformFrame(frame.getClassName())) {
                        seen.merge(thread.getName().replaceAll("[0-9]+", "N") + " in "
                                + frame.getClassName() + "." + frame.getMethodName(), 1, Integer::sum);
                        break;
                    }
                }
            }
            Thread.sleep(intervalMillis);
        }
        return seen.toString();
    }

    private static boolean isPlatformFrame(String className) {
        return className.startsWith("java.") || className.startsWith("jdk.")
                || className.startsWith("sun.") || className.startsWith("com.sun.")
                || className.startsWith("org.junit.");
    }

    private static boolean isAgentFed(DetectorType type) {
        return DetectorExposure.feedOf(type) == DetectorFeed.AGENT;
    }

    private static Contract contractOf(String testMethod) {
        Subject subject = Corpus.byTestMethod(testMethod);
        return subject == null ? null : subject.contract();
    }
}
