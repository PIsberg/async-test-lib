package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ConfinedArenaThreadEscapeDetector;
import se.deversity.asynctest.example.service.PacketParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for PacketParser.
 *
 * ========================================================================
 * DETECTOR: ConfinedArenaThreadEscapeDetector
 *           (DetectorType.CONFINED_ARENA_THREAD_ESCAPE)
 * ========================================================================
 *
 * Arena javadoc, on ofConfined(): "Returns a new arena that has an
 * unbounded lifetime, and is confined to the current thread." Access from
 * any other thread throws WrongThreadException.
 *
 * That exception is the merciful outcome. The one worth catching in a test
 * is the other one: a confined arena frees its memory at the closing brace
 * of its try-with-resources, and it does not wait for work it handed to a
 * pool. A task that reaches the segment after that point is reading memory
 * the allocator has already reclaimed.
 *
 * THE BUG:
 *   - a segment allocated from Arena.ofConfined() is captured by a task
 *     submitted to a pool, so a thread that does not own the arena touches
 *     it, and may do so after the arena has closed
 *
 * THE FIX:
 *   - keep the work on the owning thread, or allocate from
 *     Arena.ofShared() when the buffer genuinely has to outlive the block
 *
 * WHY THE TYPES HERE ARE STAND-INS:
 * java.lang.foreign is a preview API on the Java 21 baseline these
 * examples compile against, so PacketParser models the arena/segment
 * lifecycle with plain objects. The detector keys on object identity and
 * the accessing thread, so what it observes is the same shape as the real
 * thing — which is also how the library's own unit tests drive it.
 */
class PacketParserTest {

    private ConfinedArenaThreadEscapeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ConfinedArenaThreadEscapeDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the owning thread does the work. Confinement holds, nothing to
    // report — and this is what the fixed code looks like.
    // -----------------------------------------------------------------------

    @Test
    void accessFromTheOwningThread_isClean() {
        var parser = new PacketParser();
        Thread owner = Thread.currentThread();
        var arena = new PacketParser.Arena("packet-scratch");
        var segment = new PacketParser.Segment(PacketParser.BUFFER_BYTES);

        detector.recordArena(arena, "packet-scratch", owner);
        detector.recordAllocation(segment, arena, "packet-scratch", PacketParser.BUFFER_BYTES);
        detector.recordAccess(segment, "packet-scratch", owner, true);
        detector.recordAccess(segment, "packet-scratch", owner, false);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Owner-thread access must be clean:\n" + report);
        assertEquals(0, parser.parseAndChecksumOnOwningThread(0));
    }

    // -----------------------------------------------------------------------
    // Part 2: a pool thread touches the segment. In production this is a
    // WrongThreadException; here it is a finding that names both threads.
    // -----------------------------------------------------------------------

    @Test
    void accessFromAnotherThread_isDetected() {
        Thread owner = Thread.currentThread();
        Thread intruder = new Thread(() -> { }, "checksum-worker");
        var arena = new PacketParser.Arena("packet-scratch");
        var segment = new PacketParser.Segment(PacketParser.BUFFER_BYTES);

        detector.recordArena(arena, "packet-scratch", owner);
        detector.recordAllocation(segment, arena, "packet-scratch", PacketParser.BUFFER_BYTES);
        detector.recordAccess(segment, "packet-scratch", intruder, true);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Cross-thread access to a confined segment must be flagged:\n" + report);
        assertTrue(report.toString().contains("packet-scratch"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 3: the worse failure. The owner leaves the try-with-resources, the
    // arena closes, and the worker's access lands on reclaimed memory.
    // -----------------------------------------------------------------------

    @Test
    void accessAfterTheArenaCloses_isDetected() {
        Thread owner = Thread.currentThread();
        Thread worker = new Thread(() -> { }, "checksum-worker");
        var arena = new PacketParser.Arena("packet-scratch");
        var segment = new PacketParser.Segment(PacketParser.BUFFER_BYTES);

        detector.recordArena(arena, "packet-scratch", owner);
        detector.recordAllocation(segment, arena, "packet-scratch", PacketParser.BUFFER_BYTES);
        detector.recordClose(arena, owner);                  // the closing brace
        detector.recordAccess(segment, "packet-scratch", worker, false);   // too late

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Use-after-close must be flagged:\n" + report);
    }
}
