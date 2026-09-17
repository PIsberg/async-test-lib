package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the calculations, classifications, and table formatting of {@link CorpusReport}.
 */
class CorpusReportTest {

    @Test
    @DisplayName("isFalsePositive: only VERDICT-tier HIGH or CRITICAL findings on THREAD_SAFE subjects are false positives")
    void isFalsePositiveClassification() {
        Subject safe = new Subject("safe_method", "lib", "SafeClass", Contract.THREAD_SAFE, "safe", "Safe.java:1");
        Subject unsafe = new Subject("unsafe_method", "lib", "UnsafeClass", Contract.NOT_THREAD_SAFE, "unsafe", "Unsafe.java:1");

        String detector = DetectorExposure.classOf(DetectorType.DEADLOCKS);

        // Safe subject + VERDICT + CRITICAL -> false positive
        assertTrue(CorpusReport.isFalsePositive(
                new CorpusRecorder.Finding("safe_method", detector, IssueSeverity.CRITICAL, TrustTier.VERDICT, "msg", "ev"),
                safe));

        // Safe subject + VERDICT + HIGH -> false positive
        assertTrue(CorpusReport.isFalsePositive(
                new CorpusRecorder.Finding("safe_method", detector, IssueSeverity.HIGH, TrustTier.VERDICT, "msg", "ev"),
                safe));

        // Safe subject + VERDICT + MEDIUM -> not false positive (below threshold)
        assertFalse(CorpusReport.isFalsePositive(
                new CorpusRecorder.Finding("safe_method", detector, IssueSeverity.MEDIUM, TrustTier.VERDICT, "msg", "ev"),
                safe));

        // Safe subject + VERDICT + LOW -> not false positive
        assertFalse(CorpusReport.isFalsePositive(
                new CorpusRecorder.Finding("safe_method", detector, IssueSeverity.LOW, TrustTier.VERDICT, "msg", "ev"),
                safe));

        // Safe subject + PROMPT + CRITICAL -> not false positive (PROMPT is not a library verdict claim)
        assertFalse(CorpusReport.isFalsePositive(
                new CorpusRecorder.Finding("safe_method", detector, IssueSeverity.CRITICAL, TrustTier.PROMPT, "msg", "ev"),
                safe));

        // Unsafe subject + VERDICT + CRITICAL -> not false positive (true positive detection)
        assertFalse(CorpusReport.isFalsePositive(
                new CorpusRecorder.Finding("unsafe_method", detector, IssueSeverity.CRITICAL, TrustTier.VERDICT, "msg", "ev"),
                unsafe));

        // Unsafe subject + VERDICT + HIGH -> not false positive (true positive detection)
        assertFalse(CorpusReport.isFalsePositive(
                new CorpusRecorder.Finding("unsafe_method", detector, IssueSeverity.HIGH, TrustTier.VERDICT, "msg", "ev"),
                unsafe));
    }

    @Test
    @DisplayName("recordingSummary: computes stated outcomes correctly for recording lane")
    void recordingSummaryReflectsOutcomes() {
        List<RecordingSubject> firing = Corpus.subjectsFor(CorpusLane.RECORDING).stream()
                .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_FIRE)
                .toList();
        List<CorpusRecorder.Finding> syntheticFindings = firing.stream()
                .map(s -> new CorpusRecorder.Finding(
                        s.testMethod(),
                        DetectorExposure.classOf(s.detector()),
                        IssueSeverity.HIGH,
                        TrustTier.VERDICT,
                        "msg",
                        "ev"))
                .toList();

        String summary = CorpusReport.recordingSummary(syntheticFindings, CorpusLane.RECORDING);
        assertNotNull(summary);
        assertTrue(summary.contains("| Lane | recording |"));
        assertTrue(summary.contains("| Subjects whose recorded calls oblige a finding | " + firing.size() + " |"));
        assertTrue(summary.contains("| ...that produced one | " + firing.size() + " |"));
        assertTrue(summary.contains("| Total findings | " + syntheticFindings.size() + " |"));
    }

    @Test
    @DisplayName("recordingExposure: includes all feed rows and paired detector rows")
    void recordingExposureIncludesFeedsAndDetectors() {
        String exposure = CorpusReport.recordingExposure(List.of(), CorpusLane.RECORDING);
        assertNotNull(exposure);
        assertTrue(exposure.contains("## Detector exposure"));
        assertTrue(exposure.contains("| AGENT |"));
        assertTrue(exposure.contains("| ZERO_CONFIG |"));
        assertTrue(exposure.contains("| RECORDING |"));
        assertTrue(exposure.contains("| **Total** | **" + DetectorTrust.DETECTOR_COUNT + "** |"));

        for (DetectorType detector : Corpus.pairedDetectors(CorpusLane.RECORDING)) {
            String detectorClass = DetectorExposure.classOf(detector);
            assertTrue(exposure.contains("`" + detectorClass + "`"),
                    "recordingExposure must list paired detector: " + detectorClass);
        }
    }
}
