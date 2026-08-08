package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link ConfinedArenaThreadEscapeDetector}.
 *
 * <p>Split deliberately in two. Most tests use stand-in objects for the segment, which exercises
 * the fallback path the detector takes when {@code MemorySegment.isAccessibleBy} cannot answer,
 * and those run identically on every JDK. The tests that need a real confined arena create one
 * reflectively and skip themselves where the FFM API is not usable, so the JDK 21 baseline stays
 * green while CI's JDK 25 job exercises the path where the JVM itself supplies the verdict.
 */
class ConfinedArenaThreadEscapeDetectorTest {

    /** Stand-in for a MemorySegment; the detector only uses its identity. */
    private static final class FakeSegment { }

    /** Stand-in for an Arena. */
    private static final class FakeArena { }

    private ConfinedArenaThreadEscapeDetector detector;
    private Thread owner;
    private Thread intruder;

    @BeforeEach
    void setUp() {
        detector = new ConfinedArenaThreadEscapeDetector();
        owner    = new Thread(() -> { }, "arena-owner");
        intruder = new Thread(() -> { }, "arena-intruder");
    }

    // ---- fallback path: no JVM answer available -------------------------------------------

    @Test
    void accessFromANonOwnerIsFlagged() {
        FakeArena arena = new FakeArena();
        FakeSegment seg = new FakeSegment();
        detector.recordArena(arena, "parseBuffer", owner);
        detector.recordAllocation(seg, arena, "parseBuffer", 1024);

        detector.recordAccess(seg, "parseBuffer", intruder, true);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A segment touched by a non-owner must be flagged");
        assertTrue(report.toString().contains("arena-intruder"),
                "The report must name the offending thread: " + report);
        assertFalse(report.structuredViolations.isEmpty(), "A structured Violation must be emitted");
    }

    @Test
    void withoutAJvmAnswerTheFindingIsConditionalAndMedium() {
        FakeArena arena = new FakeArena();
        FakeSegment seg = new FakeSegment();
        detector.recordArena(arena, "parseBuffer", owner);
        detector.recordAllocation(seg, arena, "parseBuffer", 1024);
        detector.recordAccess(seg, "parseBuffer", intruder, false);

        String report = detector.analyze().toString();
        assertTrue(report.contains("MEDIUM"),
                "Without isAccessibleBy the detector cannot prove confinement and must not "
                + "claim CRITICAL: " + report);
        assertTrue(report.contains("confirm the arena is confined"),
                "The wording must tell the reader what is unverified: " + report);
    }

    @Test
    void accessFromTheOwnerIsClean() {
        FakeArena arena = new FakeArena();
        FakeSegment seg = new FakeSegment();
        detector.recordArena(arena, "parseBuffer", owner);
        detector.recordAllocation(seg, arena, "parseBuffer", 1024);

        detector.recordAccess(seg, "parseBuffer", owner, true);

        assertFalse(detector.analyze().hasIssues(),
                "Confined memory used only by its owner is the correct twin and must stay silent");
    }

    @Test
    void accessAfterCloseIsFlaggedAsUseAfterFree() {
        FakeArena arena = new FakeArena();
        FakeSegment seg = new FakeSegment();
        detector.recordArena(arena, "parseBuffer", owner);
        detector.recordAllocation(seg, arena, "parseBuffer", 64);
        detector.recordClose(arena, owner);

        detector.recordAccess(seg, "parseBuffer", owner, false);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Access after close must be flagged");
        assertTrue(report.toString().contains("after its arena was closed"),
                "The report must name the use-after-free: " + report);
        assertTrue(report.toString().contains("64 bytes"),
                "The recorded segment size must reach the report: " + report);
    }

    @Test
    void closeFromANonOwnerIsFlagged() {
        FakeArena arena = new FakeArena();
        detector.recordArena(arena, "parseBuffer", owner);

        detector.recordClose(arena, intruder);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Closing a confined arena from another thread must be flagged");
        assertTrue(report.toString().contains("closed by thread 'arena-intruder'"),
                "The report must name the closing thread: " + report);
    }

    @Test
    void closeFromTheOwnerIsClean() {
        FakeArena arena = new FakeArena();
        detector.recordArena(arena, "parseBuffer", owner);

        detector.recordClose(arena, owner);

        assertFalse(detector.analyze().hasIssues(), "The owner closing its own arena is correct");
    }

    @Test
    void nullArgumentsAreIgnored() {
        detector.recordArena(null, "x", owner);
        detector.recordAllocation(null, null, "x", 1);
        detector.recordAccess(null, "x", owner, true);
        detector.recordAccess(new FakeSegment(), "x", null, true);
        detector.recordClose(null, owner);

        assertFalse(detector.analyze().hasIssues(), "Null arguments must be ignored, not reported");
    }

    @Test
    void analyzeIsIdempotent() {
        FakeArena arena = new FakeArena();
        FakeSegment seg = new FakeSegment();
        detector.recordArena(arena, "parseBuffer", owner);
        detector.recordAllocation(seg, arena, "parseBuffer", 8);
        detector.recordAccess(seg, "parseBuffer", intruder, true);

        assertEquals(detector.analyze().toString(), detector.analyze().toString(),
                "Repeated analyze() on quiescent state must produce identical reports");
    }

    // ---- JVM-answered path: needs a real confined arena ------------------------------------

    @Test
    void realConfinedSegmentTouchedByAnotherThreadIsCritical() throws Exception {
        Object arena = newConfinedArena();
        assumeTrue(arena != null, "FFM Arena.ofConfined() is not usable on this JDK");

        Object segment = allocate(arena, 128);
        detector.recordArena(arena, "native", Thread.currentThread());
        detector.recordAllocation(segment, arena, "native", 128);

        // A thread that is definitionally not the owner: the JVM answers isAccessibleBy = false.
        detector.recordAccess(segment, "native", new Thread(() -> { }, "other-carrier"), true);
        close(arena);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "The JVM says this thread may not touch the segment");
        assertTrue(report.toString().contains("CRITICAL"),
                "With a JVM answer the finding is a verdict, not a prompt: " + report);
        assertTrue(report.toString().contains("isAccessibleBy(thread) = false"),
                "The report must cite the evidence it used: " + report);
    }

    @Test
    void realConfinedSegmentUsedByItsOwnerIsClean() throws Exception {
        Object arena = newConfinedArena();
        assumeTrue(arena != null, "FFM Arena.ofConfined() is not usable on this JDK");

        Object segment = allocate(arena, 128);
        detector.recordArena(arena, "native", Thread.currentThread());
        detector.recordAllocation(segment, arena, "native", 128);
        detector.recordAccess(segment, "native", Thread.currentThread(), true);
        close(arena);

        assertFalse(detector.analyze().hasIssues(),
                "Owner-only use of a real confined arena must stay silent");
    }

    @Test
    void realSharedSegmentAcrossThreadsIsNotAConfinementViolation() throws Exception {
        Object arena = newSharedArena();
        assumeTrue(arena != null, "FFM Arena.ofShared() is not usable on this JDK");

        Object segment = allocate(arena, 128);
        detector.recordArena(arena, "shared", Thread.currentThread());
        detector.recordAllocation(segment, arena, "shared", 128);
        detector.recordAccess(segment, "shared", new Thread(() -> { }, "other-carrier"), true);
        close(arena);

        assertFalse(detector.analyze().hasIssues(),
                "A shared arena permits cross-thread access; the race it exposes belongs to "
                + "SharedMemorySegmentRaceDetector, not to this one");
    }

    private static Object newConfinedArena() {
        return newArena("ofConfined");
    }

    private static Object newSharedArena() {
        return newArena("ofShared");
    }

    /** {@return a new arena, or {@code null} when the FFM API is not usable on this JDK} */
    private static Object newArena(String factory) {
        try {
            Class<?> arenaCls = Class.forName("java.lang.foreign.Arena");
            Class.forName("java.lang.foreign.MemorySegment").getMethod("isAccessibleBy", Thread.class);
            return arenaCls.getMethod(factory).invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Allocates through the {@code Arena} interface rather than {@code arena.getClass()}: the
     * runtime class is a non-exported internal type, so a Method resolved from it is not
     * invocable.
     */
    private static Object allocate(Object arena, long bytes) throws ReflectiveOperationException {
        return Class.forName("java.lang.foreign.Arena")
                    .getMethod("allocate", long.class)
                    .invoke(arena, bytes);
    }

    private static void close(Object arena) throws Exception {
        ((AutoCloseable) arena).close();
    }
}
