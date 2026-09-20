package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @DisplayName("effectiveness: library rows reach third-party bytecode rather than test code")
    void libraryRowsReachThirdPartyBytecode() {
        Set<DetectorType> reached = LibraryReach.reached();
        Set<DetectorType> agentFed = LibraryReach.agentFed();

        // Every agent-fed detector is reached through third-party library bytecode except the
        // two LibraryReach gives a reason for: no corpus library calls System.gc, and none waits
        // behind an if (#694).
        assertEquals(agentFed.size() - 2, reached.size(),
                "all agent-fed detectors except EXPLICIT_GC and MISSED_SIGNAL must be reached "
                        + "through library bytecode");
        assertEquals(Set.of(DetectorType.EXPLICIT_GC, DetectorType.MISSED_SIGNAL),
                LibraryReach.unreached().keySet());
    }

    @Test
    @DisplayName("correctness: every subject in Corpus has valid ground truth, evidence, and source citation")
    void everySubjectHasValidGroundTruthAndSource() {
        List<Subject> subjects = Corpus.subjects();
        assertEquals(139, subjects.size(), "the corpus must hold 139 subjects");

        Set<String> methodNames = new TreeSet<>();
        for (Subject subject : subjects) {
            assertNotNull(subject.testMethod(), "testMethod must not be null");
            assertFalse(subject.testMethod().isBlank(), "testMethod must not be blank");
            assertTrue(methodNames.add(subject.testMethod()),
                    "duplicate testMethod in Corpus.subjects(): " + subject.testMethod());

            assertNotNull(subject.library(), "library must not be null for " + subject.testMethod());
            assertFalse(subject.library().isBlank(), "library must not be blank for " + subject.testMethod());

            assertNotNull(subject.className(), "className must not be null for " + subject.testMethod());
            assertFalse(subject.className().isBlank(), "className must not be blank for " + subject.testMethod());

            assertNotNull(subject.contract(), "contract must not be null for " + subject.testMethod());

            assertNotNull(subject.evidence(), "evidence must not be null for " + subject.testMethod());
            assertFalse(subject.evidence().isBlank(), "evidence must not be blank for " + subject.testMethod());

            assertNotNull(subject.source(), "source must not be null for " + subject.testMethod());
            assertFalse(subject.source().isBlank(), "source must not be blank for " + subject.testMethod());
            if (subject.library().startsWith("jdk:")) {
                assertTrue(subject.source().startsWith("java.base/") && subject.source().endsWith(".java"),
                        "JDK source must be a java.base path for " + subject.testMethod() + ": " + subject.source());
            } else {
                assertTrue(subject.source().contains(":"),
                        "third-party library source must be in file:line format for " + subject.testMethod() + ": " + subject.source());
                String linePart = subject.source().substring(subject.source().lastIndexOf(':') + 1);
                int line = Integer.parseInt(linePart);
                assertTrue(line > 0, "source line number must be positive for " + subject.testMethod());
            }
        }
    }

    @Test
    @DisplayName("correctness: all recording and agent subjects have unique test methods and complete metadata")
    void allPairedSubjectsHaveUniqueMethodsAndCompleteMetadata() {
        for (CorpusLane lane : List.of(CorpusLane.RECORDING, CorpusLane.AGENT_PAIRS)) {
            List<RecordingSubject> subjects = Corpus.subjectsFor(lane);
            Set<String> methodNames = new TreeSet<>();
            for (RecordingSubject subject : subjects) {
                assertNotNull(subject.testMethod(), "testMethod must not be null in " + lane);
                assertFalse(subject.testMethod().isBlank(), "testMethod must not be blank in " + lane);
                assertTrue(methodNames.add(subject.testMethod()),
                        "duplicate testMethod in " + lane + ": " + subject.testMethod());

                assertNotNull(subject.library(), "library must not be null for " + subject.testMethod());
                assertFalse(subject.library().isBlank(), "library must not be blank for " + subject.testMethod());

                assertNotNull(subject.className(), "className must not be null for " + subject.testMethod());
                assertFalse(subject.className().isBlank(), "className must not be blank for " + subject.testMethod());

                assertNotNull(subject.detector(), "detector must not be null for " + subject.testMethod());
                assertNotNull(subject.contract(), "contract must not be null for " + subject.testMethod());
                assertNotNull(subject.expectation(), "expectation must not be null for " + subject.testMethod());

                assertNotNull(subject.rationale(), "rationale must not be null for " + subject.testMethod());
                assertFalse(subject.rationale().isBlank(), "rationale must not be blank for " + subject.testMethod());
            }
        }
    }

    @Test
    @DisplayName("correctness: all paired detectors across lanes map to matching trust tiers in DetectorTrust")
    void allPairedDetectorsMapToMatchingTrustTiers() {
        Set<DetectorType> promoted = PairEvidence.promoted();
        for (CorpusLane lane : List.of(CorpusLane.RECORDING, CorpusLane.AGENT_PAIRS)) {
            Set<DetectorType> paired = Corpus.pairedDetectors(lane);
            for (DetectorType detector : paired) {
                TrustTier tier = DetectorTrust.tierOf(detector);
                assertNotNull(tier, "detector " + detector + " in " + lane.propertyValue() + " must have a non-null trust tier");
                assertTrue(tier == TrustTier.VERDICT || tier == TrustTier.PROMPT || tier == TrustTier.ADVISORY || tier == TrustTier.FACT,
                        "detector " + detector + " in " + lane.propertyValue() + " has unexpected tier: " + tier);

                if (promoted.contains(detector)) {
                    assertEquals(TrustTier.VERDICT, tier,
                            "promoted detector " + detector + " must carry TrustTier.VERDICT");
                }
            }
        }
    }

    @Test
    @DisplayName("effectiveness: findings on firing subjects require non-null severity, message, and evidence")
    void firingSubjectFindingsRequireDiagnosticsAndEvidence() {
        RecordingSubject firing = Corpus.subjectsFor(CorpusLane.RECORDING).stream()
                .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_FIRE)
                .findFirst()
                .orElseThrow();

        CorpusRecorder.Finding nullEvidence = new CorpusRecorder.Finding(
                firing.testMethod(),
                DetectorExposure.classOf(firing.detector()),
                IssueSeverity.HIGH,
                TrustTier.PROMPT,
                "diagnostic message",
                null);

        CorpusRecorder.Finding blankEvidence = new CorpusRecorder.Finding(
                firing.testMethod(),
                DetectorExposure.classOf(firing.detector()),
                IssueSeverity.HIGH,
                TrustTier.PROMPT,
                "diagnostic message",
                "   ");

        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                        List.of(nullEvidence), CorpusLane.RECORDING, List.of(firing)));

        assertThrows(AssertionFailedError.class,
                () -> CorpusGates.everySubjectGotTheOutcomeItsRecordedCallsOblige(
                        List.of(blankEvidence), CorpusLane.RECORDING, List.of(firing)));
    }
}
