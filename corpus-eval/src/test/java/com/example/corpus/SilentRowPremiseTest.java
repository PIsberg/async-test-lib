package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that silent rows reach their detectors in {@link SilentRowPremise}.
 */
class SilentRowPremiseTest {

    @Test
    @DisplayName("every real silent row in CorpusRecordingLaneTest reaches its detector")
    void realSilentRowsReachTheirDetectors() {
        List<String> broken = SilentRowPremise.rowsThatNeverReachTheirDetector();
        assertTrue(broken.isEmpty(),
                "all silent rows in the recording lane must address their detector: " + broken);
    }

    @Test
    @DisplayName("a silent row omitting its detector call is reported as broken")
    void silentRowOmittingDetectorIsReported() {
        String fakeSource = """
                class FakeLane {
                    void silentRow() {
                        int a = 1 + 2;
                    }
                }
                """;
        RecordingSubject subject = new RecordingSubject(
                "silentRow", "jdk:21", "java.lang.Object",
                DetectorType.DEADLOCKS, Contract.THREAD_SAFE,
                RecordingSubject.Expectation.MUST_STAY_SILENT, "rationale");

        List<String> broken = SilentRowPremise.rowsThatNeverReachTheirDetector(fakeSource, List.of(subject));
        assertFalse(broken.isEmpty(), "silent row that makes no call must be caught");
        assertTrue(broken.get(0).contains("silentRow is the MUST_STAY_SILENT row for DEADLOCKS"));
    }

    @Test
    @DisplayName("a silent row calling its detector through a helper is accepted")
    void silentRowReachingDetectorViaHelperIsAccepted() {
        String fakeSource = """
                class FakeLane {
                    void silentRow() {
                        myHelper();
                    }
                    private void myHelper() {
                        AsyncTestContext.deadlockDetector().recordLockAcquire("T1", "L1");
                    }
                }
                """;
        RecordingSubject subject = new RecordingSubject(
                "silentRow", "jdk:21", "java.lang.Object",
                DetectorType.DEADLOCKS, Contract.THREAD_SAFE,
                RecordingSubject.Expectation.MUST_STAY_SILENT, "rationale");

        List<String> broken = SilentRowPremise.rowsThatNeverReachTheirDetector(fakeSource, List.of(subject));
        assertTrue(broken.isEmpty(), "silent row reaching detector via helper must pass: " + broken);
    }

    @Test
    @DisplayName("MUST_FIRE rows are ignored by SilentRowPremise")
    void mustFireRowsAreIgnored() {
        String fakeSource = "class FakeLane { void loudRow() { } }";
        RecordingSubject subject = new RecordingSubject(
                "loudRow", "jdk:21", "java.lang.Object",
                DetectorType.DEADLOCKS, Contract.THREAD_SAFE,
                RecordingSubject.Expectation.MUST_FIRE, "rationale");

        List<String> broken = SilentRowPremise.rowsThatNeverReachTheirDetector(fakeSource, List.of(subject));
        assertTrue(broken.isEmpty(), "MUST_FIRE rows must not be checked by SilentRowPremise: " + broken);
    }
}
