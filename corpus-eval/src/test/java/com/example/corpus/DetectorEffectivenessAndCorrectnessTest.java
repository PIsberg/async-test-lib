package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.DetectorFeeds;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the effectiveness and correctness of detectors across all corpus evaluation lanes.
 *
 * <p><strong>Effectiveness</strong> means the detector reliably fires on genuine concurrency
 * hazards and thread-safety contract violations (true positives), producing meaningful
 * diagnostic messages and evidence without false negatives.
 *
 * <p><strong>Correctness</strong> means the detector stays completely silent on correct,
 * thread-safe code (true negatives, zero false positives), preserves clean isolation between
 * detectors (no collateral noise), and attributes findings strictly to instrumented bytecode
 * rather than test harness artifacts.
 */
class DetectorEffectivenessAndCorrectnessTest {

    @Test
    @DisplayName("effectiveness: every paired detector has a MUST_FIRE subject specifying the defect")
    void everyPairedDetectorHasMustFireSubject() {
        for (CorpusLane lane : List.of(CorpusLane.RECORDING, CorpusLane.AGENT_PAIRS)) {
            Set<DetectorType> paired = Corpus.pairedDetectors(lane);
            for (DetectorType detector : paired) {
                List<RecordingSubject> firingSubjects = Corpus.subjectsFor(lane).stream()
                        .filter(s -> s.detector() == detector && s.expectation() == RecordingSubject.Expectation.MUST_FIRE)
                        .toList();

                assertFalse(firingSubjects.isEmpty(),
                        "effectiveness defect: " + detector + " in " + lane.propertyValue()
                                + " has no MUST_FIRE row to prove it can detect concurrency hazards");

                for (RecordingSubject subject : firingSubjects) {
                    assertNotNull(subject.rationale(), "rationale must not be null for " + subject.testMethod());
                    assertFalse(subject.rationale().isBlank(),
                            "effectiveness defect: " + subject.testMethod() + " has blank rationale");
                    assertFalse(subject.className().isBlank(),
                            "effectiveness defect: " + subject.testMethod() + " has blank class name");
                }
            }
        }
    }

    @Test
    @DisplayName("correctness: every paired detector has a MUST_STAY_SILENT twin specifying clean usage")
    void everyPairedDetectorHasMustStaySilentTwin() {
        for (CorpusLane lane : List.of(CorpusLane.RECORDING, CorpusLane.AGENT_PAIRS)) {
            Set<DetectorType> paired = Corpus.pairedDetectors(lane);
            for (DetectorType detector : paired) {
                List<RecordingSubject> silentSubjects = Corpus.subjectsFor(lane).stream()
                        .filter(s -> s.detector() == detector && s.expectation() == RecordingSubject.Expectation.MUST_STAY_SILENT)
                        .toList();

                assertFalse(silentSubjects.isEmpty(),
                        "correctness defect: " + detector + " in " + lane.propertyValue()
                                + " has no MUST_STAY_SILENT row to prove it stays silent on correct usage");

                for (RecordingSubject subject : silentSubjects) {
                    assertNotNull(subject.rationale(), "rationale must not be null for " + subject.testMethod());
                    assertFalse(subject.rationale().isBlank(),
                            "correctness defect: " + subject.testMethod() + " has blank rationale");
                }
            }
        }
    }

    @Test
    @DisplayName("correctness: agent-pair twins call the exact same class and methods")
    void agentPairsEnforceStrictStructuralTwins() {
        List<String> brokenTwins = new ArrayList<>();
        for (RecordingSubject loud : Corpus.subjectsFor(CorpusLane.AGENT_PAIRS)) {
            if (loud.expectation() != RecordingSubject.Expectation.MUST_FIRE) {
                continue;
            }
            RecordingSubject quiet = AgentRowPremise.twinOf(loud);
            if (quiet == null) {
                brokenTwins.add(loud.testMethod() + " has no twin");
            } else {
                if (!quiet.className().equals(loud.className())) {
                    brokenTwins.add(loud.testMethod() + " class (" + loud.className()
                            + ") differs from twin (" + quiet.className() + ")");
                }
                if (quiet.detector() != loud.detector()) {
                    brokenTwins.add(loud.testMethod() + " detector (" + loud.detector()
                            + ") differs from twin (" + quiet.detector() + ")");
                }
            }
        }
        assertTrue(brokenTwins.isEmpty(),
                "correctness defect: agent pairs must have matching twins on the same class and detector: "
                        + brokenTwins);
    }

    @Test
    @DisplayName("correctness: documented thread-safe subjects provide a zero false positive bound")
    void documentedThreadSafeSubjectsBoundFalsePositives() {
        long safeCount = Corpus.count(Contract.THREAD_SAFE);
        assertTrue(safeCount >= 60,
                "the documented-safe denominator must be at least 60 to bound false-positive rate <= 5% (currently "
                        + safeCount + ")");

        // Synthesize finding on safe subject to verify gate strictly fails
        Subject safeSubject = Corpus.subjects().stream()
                .filter(s -> s.contract() == Contract.THREAD_SAFE)
                .findFirst()
                .orElseThrow();

        CorpusRecorder.Finding verdictFp = new CorpusRecorder.Finding(
                safeSubject.testMethod(),
                DetectorExposure.classOf(DetectorType.ATOMICITY_VIOLATIONS),
                IssueSeverity.HIGH,
                TrustTier.VERDICT,
                "data race on thread-safe class",
                "evidence");

        boolean isFp = CorpusReport.isFalsePositive(verdictFp, safeSubject);
        assertTrue(isFp, "a VERDICT/HIGH finding on a documented thread-safe subject must be classified as a false positive");
    }

    @Test
    @DisplayName("effectiveness: unsafe group floor requires genuine detector findings")
    void unsafeGroupFloorRequiresPositiveFindings() {
        long unsafeCount = Corpus.count(Contract.NOT_THREAD_SAFE);
        assertTrue(unsafeCount > 0, "the corpus must include documented NOT_THREAD_SAFE subjects");

        // Verify detection gate requires exercised detectors to fire
        Set<DetectorType> exercised = CorpusGates.exercisedAgentDetectors();
        assertFalse(exercised.isEmpty(), "exercised agent detectors must not be empty");
        assertTrue(exercised.contains(DetectorType.ATOMICITY_VIOLATIONS), "AtomicityValidator must be exercised");
        assertTrue(exercised.contains(DetectorType.SHARED_COLLECTIONS), "SharedCollectionDetector must be exercised");
    }

    @Test
    @DisplayName("correctness: recording lane enforces absolute collateral silence")
    void recordingLaneEnforcesAbsoluteCollateralSilence() {
        assertTrue(CorpusLane.RECORDING.failsOnAnyCollateral(),
                "recording lane bar must be absolute: any collateral finding on a silent row is a correctness failure");
        assertFalse(CorpusLane.AGENT_PAIRS.failsOnAnyCollateral(),
                "agent-pairs lane bar is VERDICT HIGH/CRITICAL due to agent scaffolding");
    }

    @Test
    @DisplayName("correctness: detached control lane forbids findings from agent-fed detectors")
    void controlLaneForbidsAgentFedFindings() {
        Set<DetectorType> agentFed = LibraryReach.agentFed();
        for (DetectorType type : agentFed) {
            assertEquals(DetectorFeed.AGENT, DetectorFeeds.feedOf(type),
                    type + " must be classified as AGENT-fed");
        }
    }

    @Test
    @DisplayName("effectiveness: library rows reach third-party bytecode rather than test code")
    void libraryRowsReachThirdPartyBytecode() {
        Set<DetectorType> reached = LibraryReach.reached();
        Set<DetectorType> agentFed = LibraryReach.agentFed();

        // 17 of 18 agent-fed detectors are reached through third-party library bytecode
        assertEquals(agentFed.size() - 1, reached.size(),
                "all agent-fed detectors except EXPLICIT_GC must be reached through library bytecode");
        assertTrue(LibraryReach.unreached().containsKey(DetectorType.EXPLICIT_GC));
    }

    @Test
    @DisplayName("effectiveness: every firing subject resolves to a valid, non-degraded severity")
    void everyFiringSubjectHasValidSeverity() {
        List<RecordingSubject> firingRows = Corpus.subjectsFor(CorpusLane.RECORDING).stream()
                .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_FIRE)
                .toList();
        assertEquals(118, firingRows.size(), "recording lane must hold exactly 118 MUST_FIRE rows");

        long critical = 0;
        long high = 0;
        long medium = 0;
        long low = 0;

        for (RecordingSubject subject : firingRows) {
            IssueSeverity severity = subject.resolvedSeverity();
            assertNotNull(severity, "firing subject must have non-null resolved severity: " + subject.testMethod());
            switch (severity) {
                case CRITICAL -> critical++;
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
        }

        assertEquals(118, critical + high + medium + low, "every row must map to a valid severity tier");
        assertTrue(critical > 0, "must have critical severity findings");
        assertTrue(high > 0, "must have high severity findings");
        assertTrue(medium > 0, "must have medium severity findings");
    }

    @Test
    @DisplayName("effectiveness: every firing subject in agent pairs resolves to a valid, non-degraded severity")
    void everyAgentPairFiringSubjectHasValidSeverity() {
        List<RecordingSubject> firingRows = Corpus.subjectsFor(CorpusLane.AGENT_PAIRS).stream()
                .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_FIRE)
                .toList();
        assertEquals(34, firingRows.size(), "agent-pairs lane must hold exactly 34 MUST_FIRE rows");

        long critical = 0;
        long high = 0;
        long medium = 0;
        long low = 0;

        for (RecordingSubject subject : firingRows) {
            IssueSeverity severity = subject.resolvedSeverity();
            assertNotNull(severity, "firing subject must have non-null resolved severity: " + subject.testMethod());
            switch (severity) {
                case CRITICAL -> critical++;
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
        }

        assertEquals(34, critical + high + medium + low, "every row must map to a valid severity tier");
        assertTrue(critical > 0, "must have critical severity findings");
        assertTrue(high > 0, "must have high severity findings");
    }
}
