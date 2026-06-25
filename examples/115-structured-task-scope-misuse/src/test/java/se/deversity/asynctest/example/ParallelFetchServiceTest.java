package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.StructuredTaskScopeMisuseDetector;
import se.deversity.asynctest.example.service.ParallelFetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ParallelFetchService.
 *
 * ========================================================================
 * DETECTOR: StructuredTaskScopeMisuseDetector  (standalone — JDK 25/26, JEP 505)
 * ========================================================================
 *
 * The JDK 25 StructuredTaskScope.open(Joiner) API enforces a strict lifecycle:
 *
 *     open → fork* → join → get* → close   (try-with-resources)
 *
 * Breaking it does not just produce a bad result — it throws, leaks subtasks, or
 * reads from an incomplete subtask. StructuredTaskScopeMisuseDetector models each
 * scope as a state machine and flags the transitions the runtime rejects:
 *
 *   - fork() after join()          → IllegalStateException
 *   - Subtask.get() before join()  → IllegalStateException (partial result)
 *   - fork()/join() off-owner      → WrongThreadException (scope is confined)
 *   - close() without join()       → running subtasks cancelled, work lost
 *
 * The detector is standalone (not wired into @AsyncTest) — instantiate it, record
 * lifecycle events, then assert on analyze().
 */
class ParallelFetchServiceTest {

    private ParallelFetchService service;
    private StructuredTaskScopeMisuseDetector detector;

    @BeforeEach
    void setUp() {
        service = new ParallelFetchService();
        detector = new StructuredTaskScopeMisuseDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: correct lifecycle — open, fork, join, read, close. No misuse.
    // -----------------------------------------------------------------------

    @Test
    void correctLifecycle_isClean() throws Exception {
        Thread owner = Thread.currentThread();

        detector.recordScopeOpened("fetch", owner);
        detector.recordFork("fetch", "a", owner);
        detector.recordFork("fetch", "b", owner);
        List<String> results = service.fetchAll(List.of("a", "b"));   // real fan-out
        detector.recordJoin("fetch", owner);
        detector.recordResultRead("fetch", "a", owner);
        detector.recordResultRead("fetch", "b", owner);
        detector.recordScopeClosed("fetch", owner);

        assertEquals(List.of("result-for-a", "result-for-b"), results);
        assertFalse(detector.analyze().hasIssues(), () -> "Expected clean lifecycle:\n" + detector.analyze());
    }

    // -----------------------------------------------------------------------
    // Part 2: fork after join — the scope no longer accepts work.
    // -----------------------------------------------------------------------

    @Test
    void forkAfterJoin_isDetected() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("fetch", owner);
        detector.recordFork("fetch", "a", owner);
        detector.recordJoin("fetch", owner);
        detector.recordFork("fetch", "late", owner);   // BUG: fork after join

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getForkAfterJoinIssues().size());
        assertTrue(report.getForkAfterJoinIssues().get(0).contains("IllegalStateException"));
    }

    // -----------------------------------------------------------------------
    // Part 3: result read before join — may read a partial result.
    // -----------------------------------------------------------------------

    @Test
    void resultBeforeJoin_isDetected() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("fetch", owner);
        detector.recordFork("fetch", "a", owner);
        detector.recordResultRead("fetch", "a", owner);  // BUG: read before join

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getResultBeforeJoinIssues().size());
    }

    // -----------------------------------------------------------------------
    // Part 4: owner confinement — fork from a non-owner thread.
    // -----------------------------------------------------------------------

    @Test
    void forkFromNonOwnerThread_isDetected() throws Exception {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("fetch", owner);

        Thread other = new Thread(() -> detector.recordFork("fetch", "x", Thread.currentThread()));
        other.start();
        other.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getConfinementIssues().size());
        assertTrue(report.getConfinementIssues().get(0).contains("WrongThreadException"));
    }

    // -----------------------------------------------------------------------
    // Part 5: close without join — running subtasks are cancelled.
    // -----------------------------------------------------------------------

    @Test
    void closeWithoutJoin_isDetected() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("fetch", owner);
        detector.recordFork("fetch", "a", owner);
        detector.recordScopeClosed("fetch", owner);     // BUG: never joined

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getMissingJoinIssues().size());
    }
}
