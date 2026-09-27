package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SharedMemorySegmentRaceDetector}.
 *
 * <p>The detector reasons about byte ranges and recorded locks, never about the segment itself,
 * so a stand-in object is a complete substitute and the scenarios are deterministic. The guard
 * tests are the interesting ones: they are what separates this detector's HIGH findings from the
 * "two threads touched it" prompts that the older access-pattern detectors are limited to.
 */
class SharedMemorySegmentRaceDetectorTest {

    /** Stand-in for a MemorySegment; the detector only uses its identity. */
    private static final class FakeSegment { }

    private SharedMemorySegmentRaceDetector detector;
    private FakeSegment segment;
    private Thread t1;
    private Thread t2;

    @BeforeEach
    void setUp() {
        detector = new SharedMemorySegmentRaceDetector();
        segment  = new FakeSegment();
        t1       = new Thread(() -> { }, "seg-1");
        t2       = new Thread(() -> { }, "seg-2");
    }

    @Test
    void overlappingUnguardedWriteAndReadIsFlagged() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1);
        detector.recordAccess(segment, "ringBuffer", 4, 8, false, t2);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Overlapping access with a write must be flagged");
        assertTrue(report.toString().contains("bytes [4,8)"),
                "The report must name the overlapping range: " + report);
        assertTrue(report.toString().contains("MEDIUM"),
                "With no lock recorded the finding is a prompt, not a verdict: " + report);
    }

    @Test
    void disjointRangesAreClean() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1);
        detector.recordAccess(segment, "ringBuffer", 8, 8, true, t2);

        assertFalse(detector.analyze().hasIssues(),
                "Partitioning the segment with asSlice is the correct twin: disjoint ranges "
                + "cannot race and must stay silent");
    }

    @Test
    void concurrentReadsAreClean() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, false, t1);
        detector.recordAccess(segment, "ringBuffer", 0, 8, false, t2);

        assertFalse(detector.analyze().hasIssues(), "Read/read overlap is always safe");
    }

    @Test
    void sameThreadOverlapIsClean() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1);
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1);

        assertFalse(detector.analyze().hasIssues(), "A thread cannot race with itself");
    }

    @Test
    void agreedGuardSuppressesTheFinding() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1, "bufferLock");
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t2, "bufferLock");

        assertFalse(detector.analyze().hasIssues(),
                "Two threads holding the same monitor are mutually excluded; this is the "
                + "lock model that keeps the detector off correctly synchronized code");
    }

    @Test
    void conflictingGuardsAreFlaggedAtHigh() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1, "lockA");
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t2, "lockB");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Different monitors do not exclude each other");
        assertTrue(report.toString().contains("HIGH"),
                "Disagreeing locks is a defect, reported at HIGH: " + report);
        assertTrue(report.toString().contains("guards: lockA vs lockB"),
                "The report must name both monitors: " + report);
    }

    @Test
    void oneSideGuardedAndOneSideNotIsFlaggedAtHigh() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1, "bufferLock");
        detector.recordAccess(segment, "ringBuffer", 0, 8, false, t2);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A lock only one side takes is not mutual exclusion");
        assertTrue(report.toString().contains("HIGH"),
                "Half-guarded access is a defect, not a prompt: " + report);
        assertTrue(report.toString().contains("guards: bufferLock vs none"),
                "The report must show which side was unguarded: " + report);
    }

    @Test
    void accessAfterCloseIsCritical() {
        detector.recordClose(segment, "ringBuffer");
        detector.recordAccess(segment, "ringBuffer", 0, 8, false, t1);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Access after close must be flagged");
        assertTrue(report.toString().contains("CRITICAL"),
                "Use-after-free is unconditional: " + report);
        assertTrue(report.toString().contains("after its arena was closed"),
                "The report must name the use-after-free: " + report);
    }

    @Test
    void accessAfterARecordedCloseIsGradedAFactNotAVerdict() {
        detector.recordClose(segment, "ringBuffer");
        detector.recordAccess(segment, "ringBuffer", 0, 8, false, t1);

        List<GradedFindings.Grade> grades = detector.analyze().grades();
        assertEquals(1, grades.size(), grades.toString());
        GradedFindings.Grade grade = grades.get(0);
        assertEquals(IssueSeverity.CRITICAL, grade.severity(), "the path changes the tier, not the severity");
        assertEquals(TrustTier.FACT, grade.tier(),
                "recordClose is the test saying the arena closed; nothing asked the JVM (#753): " + grade);
        assertEquals(DetectorTrust.Evidence.ASSERTED, grade.evidence(), grade.toString());
        assertEquals(grades, DetectorTrust.clampToCap("SharedMemorySegmentRaceDetector", grades),
                "graded at its own evidence, nothing is left for the report path to lower");
    }

    @Test
    void overlapFindingsStayPromptsWhateverTheirSeverity() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1, "lockA");
        detector.recordAccess(segment, "ringBuffer", 4, 8, true, t2, "lockB");
        detector.recordAccess(segment, "ringBuffer", 64, 8, true, t1);
        detector.recordAccess(segment, "ringBuffer", 68, 8, true, t2);

        List<GradedFindings.Grade> grades = detector.analyze().grades();
        assertEquals(List.of(IssueSeverity.HIGH, IssueSeverity.MEDIUM),
                grades.stream().map(GradedFindings.Grade::severity).toList(), grades.toString());
        assertTrue(grades.stream().allMatch(g -> g.tier() == TrustTier.PROMPT),
                "an overlap rests on the guards the test named, or none: " + grades);
        assertEquals(List.of(DetectorTrust.Evidence.CONTEXTUAL, DetectorTrust.Evidence.CONTEXT_FREE),
                grades.stream().map(GradedFindings.Grade::evidence).toList(),
                "conflicting guards consulted declared locks, an unguarded overlap consulted none");
    }

    @Test
    void accessBeforeCloseIsClean() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, false, t1);
        detector.recordClose(segment, "ringBuffer");

        assertFalse(detector.analyze().hasIssues(),
                "Closing after the last access is correct lifecycle management");
    }

    @Test
    void separateSegmentsDoNotOverlap() {
        FakeSegment other = new FakeSegment();
        detector.recordAccess(segment, "a", 0, 8, true, t1);
        detector.recordAccess(other, "b", 0, 8, true, t2);

        assertFalse(detector.analyze().hasIssues(),
                "Identical offsets in different segments are different memory");
    }

    @Test
    void trackingCapIsReportedRatherThanSilentlyTruncating() {
        for (int i = 0; i < SharedMemorySegmentRaceDetector.MAX_TRACKED_ACCESSES + 50; i++) {
            detector.recordAccess(segment, "ringBuffer", 0, 8, true, i % 2 == 0 ? t1 : t2);
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "The overlap inside the cap must still be found");
        assertTrue(report.toString().contains("exceeded the"),
                "A dropped-sample count must be reported, so a clean tail is never mistaken "
                + "for full coverage: " + report);
    }

    @Test
    void zeroLengthAccessIsIgnored() {
        detector.recordAccess(segment, "ringBuffer", 0, 0, true, t1);
        detector.recordAccess(segment, "ringBuffer", 0, 0, true, t2);

        assertFalse(detector.analyze().hasIssues(), "An empty range touches no bytes");
    }

    @Test
    void nullArgumentsAreIgnored() {
        detector.recordAccess(null, "x", 0, 8, true, t1);
        detector.recordAccess(segment, "x", 0, 8, true, null);
        detector.recordClose(null, "x");

        assertFalse(detector.analyze().hasIssues(), "Null arguments must be ignored, not reported");
    }

    @Test
    void analyzeIsIdempotent() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1);
        detector.recordAccess(segment, "ringBuffer", 4, 8, false, t2);

        assertEquals(detector.analyze().toString(), detector.analyze().toString(),
                "Repeated analyze() on quiescent state must produce identical reports");
    }

    @Test
    void structuredViolationsCarryTheSegmentLabel() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1);
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t2);

        var report = detector.analyze();
        assertFalse(report.structuredViolations.isEmpty(), "A structured Violation must be emitted");
        assertEquals("ringBuffer", report.structuredViolations.get(0).attributes().get("label"),
                "Machine-readable output must carry the label the test author chose");
    }

    @Test
    void writesFromDifferentRoundsAreNotPaired() {
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t1);
        detector.markInvocationStart();
        detector.recordAccess(segment, "ringBuffer", 0, 8, true, t2);
        assertFalse(detector.analyze().hasIssues(),
            "the runner orders rounds through its latch, so a write in round one and a write in "
                + "round two cannot race");
    }

    /**
     * #849, pinning #812: once the segment holds {@code MAX_TRACKED_ACCESSES} accesses, a further
     * access is only counted as dropped, and neither that nor the state lookup may allocate. The
     * accesses kept below the cap are the analysis input, one record each.
     */
    @Test
    void recordingPastTheAccessCapAllocatesNothingPerAccess() throws InterruptedException {
        Thread filler = Thread.currentThread();
        for (int i = 0; i < SharedMemorySegmentRaceDetector.MAX_TRACKED_ACCESSES; i++) {
            detector.recordAccess(segment, "ringBuffer", 0, 8, true, filler, "lock");
        }

        long bytes = RecordPathAllocation.measuredBytes(() -> detector.recordAccess(
                segment, "ringBuffer", 0, 8, true, Thread.currentThread(), "lock"));

        assertTrue(bytes < RecordPathAllocation.CEILING, "recording past the cap on a "
                + "segment the detector already tracks allocated " + bytes + " bytes over "
                + RecordPathAllocation.MEASURED_CALLS + " accesses");
    }
}
