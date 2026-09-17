package com.example.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shows that {@link CorpusGates} can fail, one gate at a time.
 *
 * <p>Every number this module publishes is trusted because a gate would have caught it being
 * wrong, and nothing checked that any gate could catch anything. A gate here is a stream filtered
 * down to a list that must be empty, which is the shape that passes silently once the filter stops
 * matching: rename what a {@code Violation} carries as its detector, or change how a subject is
 * attributed, and several of these would go green over an input they exist to reject. The lanes
 * cannot notice, because a lane that is behaving produces exactly the input a broken gate also
 * accepts.
 *
 * <p>The module recognised this once by hand already:
 * {@code everyCorpusBackedVerdictResolvesToItsPair} asserts {@code lines > 0} with the message
 * "this gate passed by reading nothing". This class is that idea applied to every gate whose input
 * can be synthesised.
 *
 * <p>Each test feeds one gate the minimal input it must reject and asserts it throws. Where the
 * gate is meant to be selective rather than absolute, a second test feeds it the neighbouring
 * input it must accept: a gate that throws on everything is no more use than one that throws on
 * nothing, and only the pair tells them apart.
 *
 * <p>All gates whose input can be synthesised, including the gates parameterized to take
 * their subjects, source, or execution parameters, are covered in both failing and accepting
 * directions.
 *
 * <p>It runs in the agent-on lane because it needs no measurement, only the gate code and the
 * static corpus. That the agent is attached in that fork is itself used once, by the test that
 * holds the control lane's attachment gate to rejecting an attached JVM.
 */
class CorpusGatesTest {

    // --- Quiescence after the last subject ------------------------------------------------------

    @Test
    @DisplayName("a subject that keeps publishing after the run fails the quiescence gate")
    void eventsAfterTheLastSubjectFailQuiescence() {
        // A TimedSemaphore timer left running published 430 events in this window on JDK 26.
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.nothingPublishesAfterTheLastSubject(1_000L, 1_300L, 250L));
    }

    @Test
    @DisplayName("a tripped quiescence gate names what was running")
    void aTrippedQuiescenceGateCarriesItsDiagnostics() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> CorpusGates.nothingPublishesAfterTheLastSubject(1_000L, 1_300L, 250L,
                        "{corpus-timer in com.example.Leak.tick=40}"));
        assertTrue(failure.getMessage().contains("corpus-timer in com.example.Leak.tick"),
                "the CI log is the only place a failure of this gate is ever read: " + failure.getMessage());
    }

    @Test
    @DisplayName("the thread sampler reports a busy thread outside platform code")
    void theSamplerSeesABusyThread() throws InterruptedException {
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread busy = new Thread(() -> {
            while (!stop.get()) {
                Math.sqrt(System.nanoTime());
            }
        }, "sampler-probe-7");
        busy.setDaemon(true);
        busy.start();
        try {
            String seen = CorpusGates.threadsInsideNonPlatformCode(20, 2);
            assertTrue(seen.contains("sampler-probe-N in com.example.corpus.CorpusGatesTest"), seen);
        } finally {
            stop.set(true);
            busy.join(1_000);
        }
    }

    @Test
    @DisplayName("a counter that stays within the allowance passes the quiescence gate")
    void aQuietWindowPassesQuiescence() {
        assertDoesNotThrow(() -> CorpusGates.nothingPublishesAfterTheLastSubject(1_000L, 1_000L, 250L));
        assertDoesNotThrow(() -> CorpusGates.nothingPublishesAfterTheLastSubject(
                1_000L, 1_000L + CorpusGates.QUIET_WINDOW_ALLOWANCE, 250L));
    }

    @Test
    @DisplayName("one event over the allowance fails the quiescence gate")
    void oneEventOverTheAllowanceFailsQuiescence() {
        // The Surefire command reader that tripped this gate on JDK 25 and 26 published 261 and 271
        // events; with it excluded the window reads 0, so the boundary is pinned where it now is.
        assertThrows(AssertionFailedError.class, () -> CorpusGates.nothingPublishesAfterTheLastSubject(
                1_000L, 1_000L + CorpusGates.QUIET_WINDOW_ALLOWANCE + 1, 250L));
    }
    // --- Attribution -------------------------------------------------------------------------

    @Test
    @DisplayName("a finding attributed to no subject fails the attribution gate")
    void anOrphanFindingFailsAttribution() {
        assertThrows(AssertionFailedError.class, () -> CorpusGates.everyFindingIsAttributed(
                List.of(finding("no_such_subject_exists", someDetectorClass())), List.of()));
    }

    @Test
    @DisplayName("a finding attributed to a real subject passes it")
    void anAttributedFindingPassesAttribution() {
        assertDoesNotThrow(() -> CorpusGates.everyFindingIsAttributed(
                List.of(finding(aSubjectWith(Contract.NOT_THREAD_SAFE).testMethod(),
                        someDetectorClass())), List.of()));
    }

    @Test
    @DisplayName("a recording finding attributed to no row fails the recording attribution gate")
    void anOrphanRecordingFindingFailsAttribution() {
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everyRecordingFindingIsAttributed(
                        List.of(finding("no_such_row_exists", someDetectorClass())),
                        CorpusLane.RECORDING));
    }

    // --- False positives ---------------------------------------------------------------------

    @Test
    @DisplayName("a VERDICT/CRITICAL finding on documented-safe code fails the false-positive gate")
    void aVerdictFindingOnSafeCodeFailsTheFalsePositiveGate() {
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.noFalsePositiveOnDocumentedThreadSafeCode(List.of(
                        finding(aSubjectWith(Contract.THREAD_SAFE).testMethod(),
                                someDetectorClass(), TrustTier.VERDICT, IssueSeverity.CRITICAL))));
    }

    @Test
    @DisplayName("the same finding on documented-unsafe code passes, so the gate reads the contract")
    void theSameFindingOnUnsafeCodePasses() {
        assertDoesNotThrow(
                () -> CorpusGates.noFalsePositiveOnDocumentedThreadSafeCode(List.of(
                        finding(aSubjectWith(Contract.NOT_THREAD_SAFE).testMethod(),
                                someDetectorClass(), TrustTier.VERDICT, IssueSeverity.CRITICAL))));
    }

    // --- Exposure ----------------------------------------------------------------------------

    @Test
    @DisplayName("a detector the feed table cannot feed here fails the exposure gate by reporting")
    void anUnexposedDetectorReportingFailsTheExposureGate() {
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everyReportingDetectorWasExposed(
                        List.of(finding(aSubjectWith(Contract.NOT_THREAD_SAFE).testMethod(),
                                DetectorExposure.classOf(unexposedIn(CorpusLane.AGENT_ON)))),
                        CorpusLane.AGENT_ON));
    }

    @Test
    @DisplayName("a detector name the trust table does not know fails it too")
    void anUnknownDetectorNameFailsTheExposureGate() {
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everyReportingDetectorWasExposed(
                        List.of(finding(aSubjectWith(Contract.NOT_THREAD_SAFE).testMethod(),
                                "NoSuchDetector")),
                        CorpusLane.AGENT_ON));
    }

    @Test
    @DisplayName("an exposed reporting detector passes the exposure gate")
    void anExposedDetectorReportingPassesTheExposureGate() {
        assertDoesNotThrow(() -> CorpusGates.everyReportingDetectorWasExposed(
                List.of(finding(aSubjectWith(Contract.NOT_THREAD_SAFE).testMethod(),
                        DetectorExposure.classOf(DetectorType.DEADLOCKS))),
                CorpusLane.AGENT_ON));
    }

    // --- Attribution -------------------------------------------------------------------------

    @Test
    @DisplayName("an unresolvable finding subject fails the attribution gate")
    void anOrphanFindingFailsTheAttributionGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyFindingIsAttributed(
                        List.of(finding("orphanSubject", someDetectorClass())), List.of(), id -> null));
    }

    @Test
    @DisplayName("an unresolvable crash subject fails the attribution gate")
    void anOrphanCrashFailsTheAttributionGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyFindingIsAttributed(
                        List.of(), List.of(new CorpusRecorder.Crash("orphanCrash", "java.lang.RuntimeException")), id -> null));
    }

    @Test
    @DisplayName("attributed findings and crashes pass the attribution gate")
    void attributedFindingsAndCrashesPassTheAttributionGate() {
        Subject subject = aSubjectWith(Contract.THREAD_SAFE);
        assertDoesNotThrow(() ->
                CorpusGates.everyFindingIsAttributed(
                        List.of(finding(subject.testMethod(), someDetectorClass())),
                        List.of(new CorpusRecorder.Crash(subject.testMethod(), "java.lang.RuntimeException")),
                        id -> subject));
    }

    @Test
    @DisplayName("an unresolvable recording finding fails the recording attribution gate")
    void anOrphanRecordingFindingFailsTheAttributionGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyRecordingFindingIsAttributed(
                        List.of(finding("orphanRecordingSubject", someDetectorClass())), id -> null));
    }

    @Test
    @DisplayName("attributed recording findings pass the recording attribution gate")
    void attributedRecordingFindingPassesTheAttributionGate() {
        RecordingSubject row = new RecordingSubject("recSubject", "lib", "Cls", DetectorType.LOCK_LEAKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        assertDoesNotThrow(() ->
                CorpusGates.everyRecordingFindingIsAttributed(
                        List.of(finding("recSubject", someDetectorClass())), id -> id.equals("recSubject") ? row : null));
    }

    // --- Detection ---------------------------------------------------------------------------

    @Test
    @DisplayName("a silent unsafe group fails the detection floor")
    void aSilentGroupFailsTheDetectionFloor() {
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.theUnsafeGroupIsDetected(List.of()));
    }

    @Test
    @DisplayName("a sweep by another detector still fails, because the two that must speak did not")
    void aSweepByAnotherDetectorStillFailsTheDetectionGate() {
        String other = DetectorExposure.classOf(
                aDetectorOutside(CorpusGates.exercisedAgentDetectors()));
        List<CorpusRecorder.Finding> everyUnsafeSubject = new ArrayList<>();
        for (Subject subject : Corpus.subjects()) {
            if (subject.contract() == Contract.NOT_THREAD_SAFE) {
                everyUnsafeSubject.add(finding(subject.testMethod(), other));
            }
        }

        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.theUnsafeGroupIsDetected(everyUnsafeSubject));
    }

    // --- The control lane --------------------------------------------------------------------

    @Test
    @DisplayName("an agent-fed detector speaking fails the control lane's silence gate")
    void anAgentFedDetectorSpeakingFailsTheControlGate() {
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.theAgentFedSetIsSilentWithoutTheAgent(
                        List.of(finding(aSubjectWith(Contract.NOT_THREAD_SAFE).testMethod(),
                                DetectorExposure.classOf(anAgentFedDetector())))));
    }

    @Test
    @DisplayName("the attachment gate rejects a control lane in this fork, which has the agent on")
    void theAttachmentGateRejectsAnAttachedControlLane() {
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.theAgentIsAttachedTheWayThisLaneRequires(CorpusLane.AGENT_OFF));
    }

    @Test
    @DisplayName("and accepts the attached lane it is running in")
    void theAttachmentGateAcceptsTheAttachedLane() {
        assertDoesNotThrow(
                () -> CorpusGates.theAgentIsAttachedTheWayThisLaneRequires(CorpusLane.AGENT_ON));
    }

    // --- Pair outcomes -----------------------------------------------------------------------

    @Test
    @DisplayName("a recording lane that reported nothing fails every MUST_FIRE row")
    void silenceEverywhereFailsTheOutcomeGate() {
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                        List.of(), CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("exactly the stated outcomes pass it")
    void theStatedOutcomesPassTheOutcomeGate() {
        assertDoesNotThrow(() -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                everyFiringRowFiring(), CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("one extra finding on a silent row's own detector fails it")
    void oneFindingOnASilentRowFailsTheOutcomeGate() {
        List<CorpusRecorder.Finding> findings = new ArrayList<>(everyFiringRowFiring());
        RecordingSubject silent = aSilentRow();
        findings.add(finding(silent.testMethod(), DetectorExposure.classOf(silent.detector())));

        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                        findings, CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("a finding with null severity fails the outcome gate: effective findings require severity")
    void aFindingWithNullSeverityFailsTheOutcomeGate() {
        List<CorpusRecorder.Finding> findings = new ArrayList<>(everyFiringRowFiring());
        RecordingSubject firing = aFiringRow();
        findings.removeIf(f -> f.subject().equals(firing.testMethod()));
        findings.add(new CorpusRecorder.Finding(firing.testMethod(),
                DetectorExposure.classOf(firing.detector()), null, TrustTier.PROMPT,
                "valid message", "evidence"));

        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                        findings, CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("a finding with a blank message fails the outcome gate: effective findings require a message")
    void aFindingWithBlankMessageFailsTheOutcomeGate() {
        List<CorpusRecorder.Finding> findings = new ArrayList<>(everyFiringRowFiring());
        RecordingSubject firing = aFiringRow();
        findings.removeIf(f -> f.subject().equals(firing.testMethod()));
        findings.add(new CorpusRecorder.Finding(firing.testMethod(),
                DetectorExposure.classOf(firing.detector()), IssueSeverity.HIGH, TrustTier.PROMPT,
                "   ", "evidence"));

        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                        findings, CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("a finding with mismatched expected severity fails the outcome gate")
    void aFindingWithMismatchedExpectedSeverityFailsTheOutcomeGate() {
        RecordingSubject firing = new RecordingSubject(
                "test_method", "JDK", "java.lang.Object",
                DetectorType.LOCK_ORDER, Contract.THREAD_SAFE,
                RecordingSubject.Expectation.MUST_FIRE,
                "locks ordered inconsistently",
                IssueSeverity.CRITICAL);
        List<CorpusRecorder.Finding> findings = List.of(
                new CorpusRecorder.Finding("test_method",
                        DetectorExposure.classOf(DetectorType.LOCK_ORDER),
                        IssueSeverity.LOW, TrustTier.PROMPT,
                        "lock order violation", "evidence"));

        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                        findings, CorpusLane.RECORDING, List.of(firing)));
    }

    @Test
    @DisplayName("a finding matching expected severity passes the outcome gate")
    void aFindingMatchingExpectedSeverityPassesTheOutcomeGate() {
        RecordingSubject firing = new RecordingSubject(
                "test_method", "JDK", "java.lang.Object",
                DetectorType.LOCK_ORDER, Contract.THREAD_SAFE,
                RecordingSubject.Expectation.MUST_FIRE,
                "locks ordered inconsistently",
                IssueSeverity.CRITICAL);
        List<CorpusRecorder.Finding> findings = List.of(
                new CorpusRecorder.Finding("test_method",
                        DetectorExposure.classOf(DetectorType.LOCK_ORDER),
                        IssueSeverity.CRITICAL, TrustTier.PROMPT,
                        "lock order violation", "evidence"));

        assertDoesNotThrow(() -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                findings, CorpusLane.RECORDING, List.of(firing)));
    }

    // --- Collateral silence ------------------------------------------------------------------

    @Test
    @DisplayName("a VERDICT finding from another detector on a silent row fails the collateral gate")
    void aVerdictCollateralFindingOnASilentRowFails() {
        RecordingSubject silent = aSilentRow();
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.noCollateralFindingOnASilentRow(
                        List.of(finding(silent.testMethod(),
                                DetectorExposure.classOf(aDetectorOtherThan(silent.detector())),
                                TrustTier.VERDICT, IssueSeverity.CRITICAL)),
                        CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("a PROMPT finding on a recording-lane silent row fails: that lane's bar is absolute")
    void aPromptCollateralFindingOnARecordingSilentRowFails() {
        RecordingSubject silent = aSilentRow();
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.noCollateralFindingOnASilentRow(
                        List.of(finding(silent.testMethod(),
                                DetectorExposure.classOf(aDetectorOtherThan(silent.detector())),
                                TrustTier.PROMPT, IssueSeverity.MEDIUM)),
                        CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("so does a VERDICT finding at LOW severity, which the agent-pair bar would allow")
    void aLowSeverityVerdictCollateralFindingFailsInTheRecordingLane() {
        RecordingSubject silent = aSilentRow();
        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.noCollateralFindingOnASilentRow(
                        List.of(finding(silent.testMethod(),
                                DetectorExposure.classOf(aDetectorOtherThan(silent.detector())),
                                TrustTier.VERDICT, IssueSeverity.LOW)),
                        CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("the same PROMPT finding passes in the agent-pair lane, where the bar is VERDICT")
    void aPromptCollateralFindingInTheAgentPairLanePasses() {
        RecordingSubject silent = aSilentRow(CorpusLane.AGENT_PAIRS);
        assertDoesNotThrow(() -> CorpusGates.noCollateralFindingOnASilentRow(
                List.of(finding(silent.testMethod(),
                        DetectorExposure.classOf(aDetectorOtherThan(silent.detector())),
                        TrustTier.PROMPT, IssueSeverity.MEDIUM)),
                CorpusLane.AGENT_PAIRS));
    }

    @Test
    @DisplayName("and so does a VERDICT finding at MEDIUM, which is the one entry that lane prints")
    void aMediumSeverityVerdictCollateralFindingInTheAgentPairLanePasses() {
        RecordingSubject silent = aSilentRow(CorpusLane.AGENT_PAIRS);
        assertDoesNotThrow(() -> CorpusGates.noCollateralFindingOnASilentRow(
                List.of(finding(silent.testMethod(),
                        DetectorExposure.classOf(aDetectorOtherThan(silent.detector())),
                        TrustTier.VERDICT, IssueSeverity.MEDIUM)),
                CorpusLane.AGENT_PAIRS));
    }

    @Test
    @DisplayName("a VERDICT finding on a firing row passes, since a second true positive is not noise")
    void aCollateralFindingOnAFiringRowPasses() {
        RecordingSubject firing = aFiringRow();
        assertDoesNotThrow(() -> CorpusGates.noCollateralFindingOnASilentRow(
                List.of(finding(firing.testMethod(),
                        DetectorExposure.classOf(aDetectorOtherThan(firing.detector())),
                        TrustTier.VERDICT, IssueSeverity.CRITICAL)),
                CorpusLane.RECORDING));
    }

    @Test
    @DisplayName("the stated outcomes carry no collateral, so the gate is not firing on the lane")
    void theStatedOutcomesCarryNoCollateral() {
        assertDoesNotThrow(() -> CorpusGates.noCollateralFindingOnASilentRow(
                everyFiringRowFiring(), CorpusLane.RECORDING));
    }

    // --- The 5 previously unexercised gates ----------------------------------------------------

    static class DummySubjectSuite {
        @se.deversity.asynctest.AsyncTest
        void subjectA() {}

        @se.deversity.asynctest.AsyncTest
        void subjectB() {}
    }

    @Test
    @DisplayName("a missing test method fails the subject exercise gate")
    void missingAsyncTestMethodFailsSubjectExerciseGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everySubjectIsExercised(DummySubjectSuite.class, Set.of("subjectA", "subjectB", "subjectC")));
    }

    @Test
    @DisplayName("an unregistered test method fails the subject exercise gate")
    void unregisteredAsyncTestMethodFailsSubjectExerciseGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everySubjectIsExercised(DummySubjectSuite.class, Set.of("subjectA")));
    }

    @Test
    @DisplayName("matching subjects pass the subject exercise gate")
    void matchingSubjectsPassSubjectExerciseGate() {
        assertDoesNotThrow(() ->
                CorpusGates.everySubjectIsExercised(DummySubjectSuite.class, Set.of("subjectA", "subjectB")));
    }

    @Test
    @DisplayName("a missing test method fails the recording subject exercise gate")
    void missingRecordingAsyncTestMethodFailsSubjectExerciseGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyRecordingSubjectIsExercised(DummySubjectSuite.class,
                        Set.of("subjectA", "subjectB", "subjectC")));
    }

    @Test
    @DisplayName("an unregistered test method fails the recording subject exercise gate")
    void unregisteredRecordingAsyncTestMethodFailsSubjectExerciseGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyRecordingSubjectIsExercised(DummySubjectSuite.class, Set.of("subjectA")));
    }

    @Test
    @DisplayName("matching subjects pass the recording subject exercise gate")
    void matchingRecordingSubjectsPassSubjectExerciseGate() {
        assertDoesNotThrow(() ->
                CorpusGates.everyRecordingSubjectIsExercised(DummySubjectSuite.class,
                        Set.of("subjectA", "subjectB")));
    }

    @Test
    @DisplayName("an unexposed paired detector fails the paired exposure gate")
    void unexposedPairedDetectorFailsPairedExposureGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyPairedDetectorIsExposed(CorpusLane.RECORDING, Set.of(unexposedIn(CorpusLane.RECORDING))));
    }

    @Test
    @DisplayName("exposed paired detectors pass the paired exposure gate")
    void exposedPairedDetectorsPassPairedExposureGate() {
        assertDoesNotThrow(() ->
                CorpusGates.everyPairedDetectorIsExposed(CorpusLane.RECORDING, Corpus.pairedDetectors(CorpusLane.RECORDING)));
    }

    @Test
    @DisplayName("empty verdict evidence fails the verdict resolution gate")
    void emptyVerdictEvidenceFailsGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyCorpusBackedVerdictResolvesToItsPair("", id -> null));
    }

    @Test
    @DisplayName("malformed verdict evidence line fails the verdict resolution gate")
    void malformedVerdictEvidenceLineFailsGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyCorpusBackedVerdictResolvesToItsPair("LOCK_LEAKS_NO_EQUALS", id -> null));
    }

    @Test
    @DisplayName("unpaired verdict evidence line fails the verdict resolution gate")
    void unpairedVerdictEvidenceLineFailsGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyCorpusBackedVerdictResolvesToItsPair("LOCK_LEAKS=onlyOneSubject", id -> null));
    }

    @Test
    @DisplayName("missing subject in verdict evidence fails the verdict resolution gate")
    void missingSubjectInVerdictEvidenceFailsGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyCorpusBackedVerdictResolvesToItsPair("LOCK_LEAKS=subFire,subSilent", id -> null));
    }

    @Test
    @DisplayName("wrong detector in verdict evidence fails the verdict resolution gate")
    void wrongDetectorInVerdictEvidenceFailsGate() {
        RecordingSubject fire = new RecordingSubject("subFire", "lib", "Cls", DetectorType.DEADLOCKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        RecordingSubject silent = new RecordingSubject("subSilent", "lib", "Cls", DetectorType.DEADLOCKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT, "rat");
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyCorpusBackedVerdictResolvesToItsPair("LOCK_LEAKS=subFire,subSilent",
                        id -> id.equals("subFire") ? fire : silent));
    }

    @Test
    @DisplayName("wrong expectation in verdict evidence fails the verdict resolution gate")
    void wrongExpectationInVerdictEvidenceFailsGate() {
        RecordingSubject wrongFire = new RecordingSubject("subSilent", "lib", "Cls", DetectorType.LOCK_LEAKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT, "rat");
        RecordingSubject rightSilent = new RecordingSubject("subSilent2", "lib", "Cls", DetectorType.LOCK_LEAKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT, "rat");
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everyCorpusBackedVerdictResolvesToItsPair("LOCK_LEAKS=subSilent,subSilent2",
                        id -> id.equals("subSilent") ? wrongFire : rightSilent));
    }

    @Test
    @DisplayName("valid verdict evidence passes the verdict resolution gate")
    void validVerdictEvidencePassesGate() {
        RecordingSubject fire = new RecordingSubject("subFire", "lib", "Cls", DetectorType.LOCK_LEAKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        RecordingSubject silent = new RecordingSubject("subSilent", "lib", "Cls", DetectorType.LOCK_LEAKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT, "rat");
        assertDoesNotThrow(() ->
                CorpusGates.everyCorpusBackedVerdictResolvesToItsPair("LOCK_LEAKS=subFire,subSilent",
                        id -> id.equals("subFire") ? fire : silent));
    }

    @Test
    @DisplayName("silent row never calling detector fails the silent row premise gate")
    void silentRowNeverCallingDetectorFailsGate() {
        String fakeSource = "void rowQuiet() { int x = 1; }";
        RecordingSubject silent = new RecordingSubject("rowQuiet", "lib", "Cls", DetectorType.LOCK_LEAKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT, "rat");
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.everySilentRowReachesItsDetector(fakeSource, List.of(silent)));
    }

    @Test
    @DisplayName("silent row calling its detector passes the silent row premise gate")
    void silentRowCallingDetectorPassesGate() {
        String fakeSource = "void rowQuiet() { AsyncTestContext.lockLeakDetector(); }";
        RecordingSubject silent = new RecordingSubject("rowQuiet", "lib", "Cls", DetectorType.LOCK_LEAKS,
                Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT, "rat");
        assertDoesNotThrow(() ->
                CorpusGates.everySilentRowReachesItsDetector(fakeSource, List.of(silent)));
    }

    @Test
    @DisplayName("agent row touching recording API fails the agent row premise gate")
    void agentRowTouchingRecordingApiFailsGate() {
        String fakeSource = "void loud() { AsyncTestContext.sharedInstanceMonitor(); }";
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.noAgentRowRecordedItsOwnFinding(fakeSource, List.of()));
    }

    @Test
    @DisplayName("agent pair dropping a call in the silent row fails the premise gate")
    void agentPairDroppingCallInSilentRowFailsGate() {
        String fakeSource = """
                void loud() { obj.methodA(); obj.methodB(); }
                void quiet() { obj.methodA(); }
                """;
        RecordingSubject loud = new RecordingSubject("loud", "lib", "Cls", DetectorType.SIMPLE_DATE_FORMAT,
                Contract.NOT_THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        RecordingSubject quiet = new RecordingSubject("quiet", "lib", "Cls", DetectorType.SIMPLE_DATE_FORMAT,
                Contract.NOT_THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT, "rat");
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.noAgentRowRecordedItsOwnFinding(fakeSource, List.of(loud, quiet)));
    }

    @Test
    @DisplayName("clean agent pair passes the premise gate")
    void cleanAgentPairPassesGate() {
        String fakeSource = """
                void loud() { obj.format(); }
                void quiet() { obj.format(); }
                """;
        RecordingSubject loud = new RecordingSubject("loud", "lib", "Cls", DetectorType.SIMPLE_DATE_FORMAT,
                Contract.NOT_THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        RecordingSubject quiet = new RecordingSubject("quiet", "lib", "Cls", DetectorType.SIMPLE_DATE_FORMAT,
                Contract.NOT_THREAD_SAFE, RecordingSubject.Expectation.MUST_STAY_SILENT, "rat");
        assertDoesNotThrow(() ->
                CorpusGates.noAgentRowRecordedItsOwnFinding(fakeSource, List.of(loud, quiet)));
    }

    // --- Library exclusion lane gate ---------------------------------------------------------

    @Test
    @DisplayName("empty library rows fails the library exclusion gate")
    void emptyLibraryRowsFailsLibraryExclusionGate() {
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.checkLibraryExclusionLane(
                        List.of(), Set.of(), 50, List.of(), List.of("com.google.common."), 0));
    }

    @Test
    @DisplayName("missing async test method fails the library exclusion gate")
    void missingAsyncTestMethodFailsLibraryExclusionGate() {
        RecordingSubject row = new RecordingSubject(
                "guavaSubject", "com.google.guava:guava", "com.google.common.cache.LocalCache",
                DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.checkLibraryExclusionLane(
                        List.of(), Set.of("otherMethod"), 50, List.of(row), List.of("com.google.common."), 50));
    }

    @Test
    @DisplayName("uncovered library package fails the library exclusion gate")
    void uncoveredLibraryPackageFailsLibraryExclusionGate() {
        RecordingSubject row = new RecordingSubject(
                "guavaSubject", "com.google.guava:guava", "com.google.common.cache.LocalCache",
                DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.checkLibraryExclusionLane(
                        List.of(), Set.of("guavaSubject"), 50, List.of(row), List.of("org.apache.commons."), 50));
    }

    @Test
    @DisplayName("execution count mismatch fails the library exclusion gate")
    void executionCountMismatchFailsLibraryExclusionGate() {
        RecordingSubject row = new RecordingSubject(
                "guavaSubject", "com.google.guava:guava", "com.google.common.cache.LocalCache",
                DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.checkLibraryExclusionLane(
                        List.of(), Set.of("guavaSubject"), 50, List.of(row), List.of("com.google.common."), 49));
    }

    @Test
    @DisplayName("still firing library row fails the library exclusion gate")
    void stillFiringLibraryRowFailsLibraryExclusionGate() {
        RecordingSubject row = new RecordingSubject(
                "guavaSubject", "com.google.guava:guava", "com.google.common.cache.LocalCache",
                DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        CorpusRecorder.Finding finding = finding("guavaSubject", DetectorExposure.classOf(DetectorType.LOCK_LEAKS));
        assertThrows(AssertionFailedError.class, () ->
                CorpusGates.checkLibraryExclusionLane(
                        List.of(finding), Set.of("guavaSubject"), 50, List.of(row), List.of("com.google.common."), 50));
    }

    @Test
    @DisplayName("clean excluded library rows pass the library exclusion gate")
    void cleanExcludedLibraryRowsPassLibraryExclusionGate() {
        RecordingSubject row = new RecordingSubject(
                "guavaSubject", "com.google.guava:guava", "com.google.common.cache.LocalCache",
                DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE, RecordingSubject.Expectation.MUST_FIRE, "rat");
        assertDoesNotThrow(() ->
                CorpusGates.checkLibraryExclusionLane(
                        List.of(), Set.of("guavaSubject"), 50, List.of(row), List.of("com.google.common."), 50));
    }

    // --- Fixtures ----------------------------------------------------------------------------

    /** {@return every MUST_FIRE row of the recording lane, reporting from its own detector} */
    private static List<CorpusRecorder.Finding> everyFiringRowFiring() {
        List<CorpusRecorder.Finding> findings = new ArrayList<>();
        for (RecordingSubject subject : Corpus.subjectsFor(CorpusLane.RECORDING)) {
            if (subject.expectation() == RecordingSubject.Expectation.MUST_FIRE) {
                findings.add(finding(subject.testMethod(),
                        DetectorExposure.classOf(subject.detector())));
            }
        }
        return findings;
    }

    private static CorpusRecorder.Finding finding(String subject, String detectorClass) {
        return finding(subject, detectorClass, TrustTier.PROMPT, IssueSeverity.HIGH);
    }

    private static CorpusRecorder.Finding finding(String subject,
                                                  String detectorClass,
                                                  TrustTier tier,
                                                  IssueSeverity severity) {
        return new CorpusRecorder.Finding(subject, detectorClass, severity, tier,
                "synthetic finding built by CorpusGatesTest", "no evidence; nothing ran");
    }

    private static Subject aSubjectWith(Contract contract) {
        return Corpus.subjects().stream()
                .filter(subject -> subject.contract() == contract)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the corpus holds no " + contract + " subject, so this gate cannot be "
                                + "shown to discriminate on the contract"));
    }

    private static RecordingSubject aSilentRow() {
        return aSilentRow(CorpusLane.RECORDING);
    }

    /**
     * {@return a {@code MUST_STAY_SILENT} row of {@code lane}}
     *
     * <p>The lane is a parameter because the collateral bar now differs by lane, so a test of the
     * agent-pair bar has to hand the gate a row that lane actually holds. Passing a recording-lane
     * row with {@code AGENT_PAIRS} would exercise nothing: {@code collateralOnSilentRows} walks
     * the lane's own subjects, so the finding would match no row and every bar would pass it.
     *
     * @param lane the lane whose rows the caller is testing
     */
    private static RecordingSubject aSilentRow(CorpusLane lane) {
        return aRowExpecting(lane, RecordingSubject.Expectation.MUST_STAY_SILENT);
    }

    private static RecordingSubject aFiringRow() {
        return aRowExpecting(CorpusLane.RECORDING, RecordingSubject.Expectation.MUST_FIRE);
    }

    private static RecordingSubject aRowExpecting(CorpusLane lane,
                                                  RecordingSubject.Expectation expectation) {
        return Corpus.subjectsFor(lane).stream()
                .filter(subject -> subject.expectation() == expectation)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the " + lane.propertyValue() + " lane has no " + expectation + " row"));
    }

    /** {@return any detector the trust table knows, where which one is beside the point} */
    private static String someDetectorClass() {
        return DetectorExposure.classOf(DetectorType.values()[0]);
    }

    private static DetectorType aDetectorOtherThan(DetectorType type) {
        for (DetectorType candidate : DetectorType.values()) {
            if (candidate != type) {
                return candidate;
            }
        }
        throw new IllegalStateException("the library ships one detector, so nothing is collateral");
    }

    private static DetectorType aDetectorOutside(Set<DetectorType> excluded) {
        for (DetectorType candidate : DetectorType.values()) {
            if (!excluded.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("every detector is one the detection gate holds to firing");
    }

    private static DetectorType anAgentFedDetector() {
        return DetectorExposure.fedBy(DetectorFeed.AGENT).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no detector is agent-fed"));
    }

    private static DetectorType unexposedIn(CorpusLane lane) {
        for (DetectorType candidate : DetectorType.values()) {
            if (!DetectorExposure.isExposed(candidate, lane)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                lane + " exposes every detector, so the exposure gate cannot be shown to fail");
    }
}
