package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SharedMemorySegmentRaceDetector;
import se.deversity.asynctest.example.service.MarketDataRingBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for MarketDataRingBuffer.
 *
 * ========================================================================
 * DETECTOR: SharedMemorySegmentRaceDetector
 *           (DetectorType.SHARED_MEMORY_SEGMENT_RACE)
 * ========================================================================
 *
 * Arena.ofShared() lifts the thread confinement that ofConfined() imposes.
 * It is tempting to read that as "thread-safe"; it means "every thread is
 * allowed to touch this". Two threads writing the same bytes of a shared
 * MemorySegment race exactly as two threads writing the same field do, and
 * off-heap memory gets no help from the Java memory model.
 *
 * THE BUG:
 *   - several feed threads write overlapping byte ranges of one shared
 *     segment, with no partitioning and no lock
 *
 * THE FIX (either one):
 *   - partition with asSlice(offset, length) so ranges are disjoint, which
 *     needs no lock at all, or
 *   - guard every access with the same monitor when ranges must overlap
 *
 * WHAT THE DETECTOR MODELS:
 * It records the [offset, offset+length) range each thread touched and
 * intersects them. Read/read overlap is silent, disjoint ranges are
 * silent, same-thread overlap is silent. An overlap involving a write from
 * two different threads is reported: MEDIUM with no lock recorded, and
 * HIGH when the two threads named *different* guards, because disagreeing
 * about which lock protects a range is a stronger signal than not
 * mentioning one.
 *
 * The segment is a stand-in object: java.lang.foreign is preview on the
 * Java 21 baseline these examples build against, and the detector's
 * interval arithmetic is identical either way.
 */
class MarketDataRingBufferTest {

    private SharedMemorySegmentRaceDetector detector;
    private MarketDataRingBuffer buffer;
    private Thread feedA;
    private Thread feedB;

    @BeforeEach
    void setUp() {
        detector = new SharedMemorySegmentRaceDetector();
        buffer = new MarketDataRingBuffer();
        feedA = new Thread(() -> { }, "feed-a");
        feedB = new Thread(() -> { }, "feed-b");
    }

    // -----------------------------------------------------------------------
    // Part 1: partitioned writers. Disjoint slices cannot race, so this is
    // clean without any lock — the answer to reach for first.
    // -----------------------------------------------------------------------

    @Test
    void writersOnTheirOwnSlots_areClean() {
        buffer.publishToOwnSlot(0, 101L);
        buffer.publishToOwnSlot(1, 202L);

        detector.recordAccess(buffer.ring(), "marketDataRing",
                MarketDataRingBuffer.slotOffset(0), MarketDataRingBuffer.SLOT_BYTES, true, feedA);
        detector.recordAccess(buffer.ring(), "marketDataRing",
                MarketDataRingBuffer.slotOffset(1), MarketDataRingBuffer.SLOT_BYTES, true, feedB);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Disjoint slices must stay silent:\n" + report);
        assertEquals(101L, buffer.readSlot(0));
        assertEquals(202L, buffer.readSlot(1));
    }

    // -----------------------------------------------------------------------
    // Part 2: both writers target slot 0. Overlapping ranges, a write on at
    // least one side, no guard — flagged, with the overlapping range named.
    // -----------------------------------------------------------------------

    @Test
    void overlappingUnguardedWrites_areDetected() {
        buffer.publishUnpartitioned(101L);

        detector.recordAccess(buffer.ring(), "marketDataRing", 0, MarketDataRingBuffer.SLOT_BYTES, true, feedA);
        detector.recordAccess(buffer.ring(), "marketDataRing", 4, MarketDataRingBuffer.SLOT_BYTES, false, feedB);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Overlapping unguarded access must be flagged:\n" + report);
        assertTrue(report.toString().contains("bytes [4,8)"),
                () -> "The report must name the overlapping range:\n" + report);
        assertTrue(report.toString().contains("MEDIUM"),
                () -> "With no lock recorded this is a prompt, not a verdict:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: the same overlap, both writers naming the same monitor. Mutual
    // exclusion holds, so the detector stays off correctly synchronized code.
    // -----------------------------------------------------------------------

    @Test
    void overlappingWritesUnderTheSameGuard_areClean() {
        buffer.publishUnderGuard(101L);
        buffer.publishUnderGuard(202L);

        detector.recordAccess(buffer.ring(), "marketDataRing", 0, MarketDataRingBuffer.SLOT_BYTES,
                true, feedA, MarketDataRingBuffer.GUARD);
        detector.recordAccess(buffer.ring(), "marketDataRing", 0, MarketDataRingBuffer.SLOT_BYTES,
                true, feedB, MarketDataRingBuffer.GUARD);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "One agreed monitor excludes the writers:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: the nastiest variant. Both threads lock — different locks. Two
    // monitors protecting one range exclude nobody, and the report says HIGH.
    // -----------------------------------------------------------------------

    @Test
    void overlappingWritesUnderDifferentGuards_areDetectedAtHigh() {
        detector.recordAccess(buffer.ring(), "marketDataRing", 0, MarketDataRingBuffer.SLOT_BYTES,
                true, feedA, "feedALock");
        detector.recordAccess(buffer.ring(), "marketDataRing", 0, MarketDataRingBuffer.SLOT_BYTES,
                true, feedB, "feedBLock");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Conflicting guards must be flagged:\n" + report);
        assertTrue(report.toString().contains("HIGH"),
                () -> "Disagreeing about the lock is a verdict, not a prompt:\n" + report);
    }
}
