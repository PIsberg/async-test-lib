package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
