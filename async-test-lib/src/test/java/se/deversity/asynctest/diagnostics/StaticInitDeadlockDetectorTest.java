package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaticInitDeadlockDetector}.
 *
 * <p>The wait-for graph is driven with unstarted {@code Thread} instances as identity carriers,
 * which is the only way to test a deadlock detector without deadlocking the test JVM: a real
 * class-initialization deadlock cannot be unwedged, so the scenario that would produce one is
 * modelled by the records it would emit rather than by actually creating it.
 */
class StaticInitDeadlockDetectorTest {

    /** Two classes whose initializers would reference each other in the deadlocking scenario. */
    static final class Config { }

    static final class Registry { }

    static final class Unrelated { }

    private StaticInitDeadlockDetector detector;
    private Thread threadA;
    private Thread threadB;

    @BeforeEach
    void setUp() {
        detector = new StaticInitDeadlockDetector();
        threadA = new Thread(() -> { }, "clinit-a");
        threadB = new Thread(() -> { }, "clinit-b");
    }

    @Test
    void mutualInitializationIsFlaggedAsACycle() {
        // A is inside Config.<clinit> and needs Registry; B is inside Registry.<clinit> and needs Config.
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitRequest(Config.class, threadB);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A mutual class-initialization wait must be flagged");
        assertTrue(report.toString().contains("class-initialization deadlock"),
                "Report must name the deadlock: " + report);
        assertTrue(report.toString().contains("CRITICAL"),
                "A recorded cycle is a verdict, reported at CRITICAL: " + report);
        assertFalse(report.structuredViolations.isEmpty(), "A structured Violation must be emitted");
    }

    @Test
    void theReportExplainsWhyThePlatformDetectorMissesIt() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitRequest(Config.class, threadB);

        assertTrue(detector.analyze().toString().contains("findDeadlockedThreads"),
                "The report must say why the JDK's own deadlock finder reports nothing here, "
                + "since that is the whole reason this detector exists separately");
    }

    @Test
    void completedInitializationIsClean() {
        // The same two classes, but serialised rather than interleaved: no cycle.
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitStart(Registry.class, threadA);
        detector.recordInitEnd(Registry.class, threadA);
        detector.recordInitEnd(Config.class, threadA);

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "Initialization that completed is the correct twin and must stay silent: " + report);
    }

    @Test
    void oneThreadWaitingOnAnUnheldClassIsClean() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitRequest(Unrelated.class, threadA);

        assertFalse(detector.analyze().hasIssues(),
                "Waiting for a class nobody is initializing is ordinary class loading");
    }

    @Test
    void aThreadWaitingOnItselfIsNotACycle() {
        // Re-entrant initialization is explicitly legal: the JVM lets the owning thread proceed.
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitRequest(Config.class, threadA);

        assertFalse(detector.analyze().hasIssues(),
                "Recursive initialization by the owning thread is legal and must not be reported");
    }

    @Test
    void endingOneInitializerReleasesTheThreadsItBlocked() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitRequest(Config.class, threadB);
        assertTrue(detector.analyze().hasIssues(), "Precondition: the cycle exists");

        StaticInitDeadlockDetector after = new StaticInitDeadlockDetector();
        after.recordInitStart(Config.class, threadA);
        after.recordInitStart(Registry.class, threadB);
        after.recordInitRequest(Registry.class, threadA);
        after.recordInitEnd(Registry.class, threadB);      // B finishes, A is released

        assertFalse(after.analyze().hasIssues(),
                "Completing an initializer must clear the waits it was blocking");
    }

    @Test
    void threeThreadCycleIsFlagged() {
        Thread threadC = new Thread(() -> { }, "clinit-c");
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitStart(Unrelated.class, threadC);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitRequest(Unrelated.class, threadB);
        detector.recordInitRequest(Config.class, threadC);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A three-way cycle must be flagged");
        assertTrue(report.toString().contains("across 3 threads"),
                "The report must state the cycle length: " + report);
    }

    @Test
    void aCycleIsReportedOnceNotOncePerThread() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitRequest(Config.class, threadB);

        assertEquals(1, detector.analyze().violations.size(),
                "Both threads are in one cycle, which is one finding, not two");
    }

    @Test
    void nullArgumentsAreIgnored() {
        detector.recordInitStart(null, threadA);
        detector.recordInitRequest(null, threadA);
        detector.recordInitEnd(null, threadA);
        detector.recordInitStart(Config.class, null);

        assertFalse(detector.analyze().hasIssues(), "Null arguments must be ignored, not reported");
    }

    @Test
    void analyzeIsIdempotent() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitRequest(Config.class, threadB);

        assertEquals(detector.analyze().toString(), detector.analyze().toString(),
                "Repeated analyze() must produce identical reports, including the cached "
                + "live-thread sample");
    }

    @Test
    void platformDeadlockDetectorFindsNothingInAHealthyJvm() {
        // Pins the premise of this detector rather than its output: a JVM with no monitor
        // deadlock reports none, so a class-init deadlock would leave this false while the
        // application is wedged.
        assertFalse(StaticInitDeadlockDetector.platformDeadlockDetectorSeesAnything(),
                "This test JVM must not be monitor-deadlocked");
    }
}
