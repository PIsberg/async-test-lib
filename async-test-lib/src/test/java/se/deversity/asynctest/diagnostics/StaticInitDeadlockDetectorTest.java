package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
    void aRecordedCycleIsGradedAFactNotAVerdict() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitRequest(Config.class, threadB);

        var grades = detector.analyze().grades();
        assertEquals(1, grades.size(), grades.toString());
        GradedFindings.Grade grade = grades.get(0);
        assertEquals(IssueSeverity.CRITICAL, grade.severity(), "the path changes the tier, not the severity");
        assertEquals(TrustTier.FACT, grade.tier(),
                "every edge of the cycle is a record call the initializers made; the JVM was not "
                        + "asked whether anything is blocked (#753): " + grade);
        assertEquals(DetectorTrust.Evidence.ASSERTED, grade.evidence(), grade.toString());
        assertEquals(grades, DetectorTrust.clampToCap("StaticInitDeadlockDetector", grades),
                "graded at its own evidence, nothing is left for the report path to lower");
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

    /**
     * Whether the JVM already held a monitor deadlock when this class loaded. Under surefire
     * ({@code reuseForks=false}) this is always false; in a shared-JVM run such as pitest's
     * coverage stage, deadlock demonstrations in other classes leak permanently deadlocked
     * threads, and the premise below is then unobservable rather than false.
     */
    private static final boolean DEADLOCKED_BEFORE_THIS_CLASS =
            StaticInitDeadlockDetector.platformDeadlockDetectorSeesAnything();

    @Test
    void platformDeadlockDetectorFindsNothingInAHealthyJvm() {
        // Pins the premise of this detector rather than its output: a JVM with no monitor
        // deadlock reports none, so a class-init deadlock would leave this false while the
        // application is wedged.
        assumeFalse(DEADLOCKED_BEFORE_THIS_CLASS,
                "another suite leaked monitor-deadlocked threads into this shared JVM before "
                + "this class loaded; the healthy-JVM premise cannot be observed here");
        assertFalse(StaticInitDeadlockDetector.platformDeadlockDetectorSeesAnything(),
                "This test JVM must not be monitor-deadlocked");
    }
    /** Released once the attribution test has sampled; until then both initializers stay parked. */
    static final java.util.concurrent.CountDownLatch RELEASE_SLOW_INITIALIZERS = new java.util.concurrent.CountDownLatch(1);
    static final java.util.concurrent.CountDownLatch SLOW_INITIALIZERS_ENTERED = new java.util.concurrent.CountDownLatch(2);

    static final class SlowFirst {
        static {
            SLOW_INITIALIZERS_ENTERED.countDown();
            awaitRelease();
        }

        static void touch() { }
    }

    static final class SlowSecond {
        static {
            SLOW_INITIALIZERS_ENTERED.countDown();
            awaitRelease();
        }

        static void touch() { }
    }

    private static void awaitRelease() {
        try {
            RELEASE_SLOW_INITIALIZERS.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * #1503: the live sample walks every thread in the JVM. Under parallel test execution two
     * threads parked in another run's initializers were this run's HIGH finding. Attributed like
     * the deadlock detector's cycles (#1385), the sample counts a parked thread only for the run
     * it belongs to, or for any run when none overlapped it.
     */
    @Test
    void parkedInitializersAreReportedOnlyByTheRunTheyBelongTo() throws InterruptedException {
        StaticInitDeadlockDetector otherRuns = new StaticInitDeadlockDetector();
        otherRuns.attributeTo(id -> false, () -> true);
        java.util.Set<Long> parkedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        StaticInitDeadlockDetector owner = new StaticInitDeadlockDetector();
        owner.attributeTo(parkedIds::contains, () -> true);
        StaticInitDeadlockDetector alone = new StaticInitDeadlockDetector();
        alone.attributeTo(id -> false, () -> false);

        Thread first = new Thread(SlowFirst::touch, "slow-init-first");
        Thread second = new Thread(SlowSecond::touch, "slow-init-second");
        parkedIds.add(first.threadId());
        parkedIds.add(second.threadId());
        first.start();
        second.start();
        try {
            assertTrue(SLOW_INITIALIZERS_ENTERED.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "both initializers must be entered");
            assertFalse(otherRuns.analyze().hasIssues(),
                    "neither parked thread is this run's, and another run was in progress");
            assertTrue(owner.analyze().hasIssues(), "the run the parked threads belong to reports them");
            assertTrue(alone.analyze().hasIssues(), "with no other run overlapping, any run may own them");
        } finally {
            RELEASE_SLOW_INITIALIZERS.countDown();
            first.join(5_000);
            second.join(5_000);
        }
    }
}
