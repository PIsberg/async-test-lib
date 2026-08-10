package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.StaticInitDeadlockDetector;
import se.deversity.asynctest.example.service.Config;
import se.deversity.asynctest.example.service.Registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for the Config / Registry static-initialiser cycle.
 *
 * ========================================================================
 * DETECTOR: StaticInitDeadlockDetector
 *           (DetectorType.STATIC_INIT_DEADLOCK)
 * ========================================================================
 *
 * JLS 12.4.2: the first thread to touch a class acquires that class's
 * initialization lock and runs <clinit>; every other thread touching the
 * same class blocks until it completes.
 *
 * Two classes whose initialisers reference each other therefore deadlock
 * when two threads enter the cycle from opposite ends:
 *
 *   thread-a: in Config.<clinit>   -> needs Registry
 *   thread-b: in Registry.<clinit> -> needs Config
 *
 * WHY THIS DETECTOR EXISTS AT ALL:
 * Class initialization locks are not monitors. They do not appear in
 * ThreadMXBean.findDeadlockedThreads(), and a thread dump shows the
 * threads as parked with no lock edge between them. The JVM's own deadlock
 * detection reports nothing. DeadlockDetector cannot see this; only a
 * detector that tracks <clinit> entry and cross-requests can.
 *
 * THE BUG:
 *   - Config.<clinit> calls Registry.lookup(), and Registry.<clinit> reads
 *     Config.ENDPOINT
 *
 * THE FIX:
 *   - break the cycle rather than reorder it: defer through a holder class
 *     (Config.LazyEndpoint below), or move the shared constant into a third
 *     class neither initialiser calls back into
 *
 * WHY THIS TEST DOES NOT ACTUALLY DEADLOCK:
 * Reproducing it for real would hang the JVM, and there is no timeout that
 * makes that safe in a test suite — the classes stay permanently
 * uninitialisable in that classloader. So the test drives the detector
 * with the same sequence of events the JVM would generate, which is how
 * the library's own unit tests cover it too. Part 3 then loads the classes
 * for real on a single thread, which is exactly the path that always
 * works and is the reason the bug survives to production.
 */
class StaticInitDeadlockTest {

    private StaticInitDeadlockDetector detector;
    private Thread threadA;
    private Thread threadB;

    @BeforeEach
    void setUp() {
        detector = new StaticInitDeadlockDetector();
        threadA = new Thread(() -> { }, "clinit-a");
        threadB = new Thread(() -> { }, "clinit-b");
    }

    // -----------------------------------------------------------------------
    // Part 1: one thread, one initialiser at a time. No cycle, no finding.
    // -----------------------------------------------------------------------

    @Test
    void sequentialInitialization_isClean() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitStart(Registry.class, threadA);
        detector.recordInitEnd(Registry.class, threadA);
        detector.recordInitEnd(Config.class, threadA);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "One thread cannot deadlock with itself:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: two threads, opposite ends of the cycle. This is the hang that
    // ThreadMXBean will never report.
    // -----------------------------------------------------------------------

    @Test
    void mutualInitialization_isDetectedAsACycle() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitRequest(Config.class, threadB);

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "A mutual class-initialization wait must be flagged:\n" + report);
        assertTrue(report.toString().contains("Config"), report.toString());
        assertTrue(report.toString().contains("Registry"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 3: completing an initialiser releases the threads waiting on it,
    // and the finding clears. This is the single-threaded startup path that
    // makes the bug invisible until the day two threads race.
    // -----------------------------------------------------------------------

    @Test
    void completingTheInitializer_clearsTheWait() {
        detector.recordInitStart(Config.class, threadA);
        detector.recordInitStart(Registry.class, threadB);
        detector.recordInitRequest(Registry.class, threadA);
        detector.recordInitEnd(Registry.class, threadB);

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "Finishing Registry.<clinit> releases the thread waiting on it:\n" + report);

        // And for real, on one thread, the classes load without complaint.
        assertNotNull(Config.describe());
        assertNotNull(Registry.DESCRIPTION);
        assertNotNull(Config.LazyEndpoint.value());
    }
}
