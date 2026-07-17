package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StructuredTaskScopeMisuseDetector}.
 */
class StructuredTaskScopeMisuseDetectorTest {

    private StructuredTaskScopeMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StructuredTaskScopeMisuseDetector();
    }

    // ---- Happy path: open -> fork -> join -> get -> close ----

    @Test
    void noIssues_forCorrectLifecycle() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordFork("s", "b", owner);
        detector.recordJoin("s", owner);
        detector.recordResultRead("s", "a", owner);
        detector.recordResultRead("s", "b", owner);
        detector.recordScopeClosed("s", owner);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected clean lifecycle: " + report);
        assertEquals(1, report.getTotalScopes());
        assertEquals(2, report.getTotalForks());
    }

    // ---- Fork after join ----

    @Test
    void detectsForkAfterJoin() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordJoin("s", owner);
        detector.recordFork("s", "late", owner); // violation

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getForkAfterJoinIssues().size());
        String issue = report.getForkAfterJoinIssues().get(0);
        assertTrue(issue.contains("late"), issue);
        assertTrue(issue.contains("IllegalStateException"), issue);
    }

    // ---- Result before join ----

    @Test
    void detectsResultReadBeforeJoin() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordResultRead("s", "a", owner); // before join — violation

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getResultBeforeJoinIssues().size());
        assertTrue(report.getResultBeforeJoinIssues().get(0).contains("a"));
    }

    @Test
    void noResultIssue_whenReadAfterJoin() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordJoin("s", owner);
        detector.recordResultRead("s", "a", owner);

        assertTrue(detector.analyze().getResultBeforeJoinIssues().isEmpty());
    }

    // ---- Owner confinement ----

    @Test
    void detectsForkFromNonOwnerThread() throws Exception {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);

        Thread other = new Thread(() -> detector.recordFork("s", "x", Thread.currentThread()));
        other.start();
        other.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getConfinementIssues().size());
        assertTrue(report.getConfinementIssues().get(0).contains("WrongThreadException"));
    }

    @Test
    void detectsJoinFromNonOwnerThread() throws Exception {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);

        Thread other = new Thread(() -> detector.recordJoin("s", Thread.currentThread()));
        other.start();
        other.join();

        assertFalse(detector.analyze().getConfinementIssues().isEmpty());
    }

    // ---- Missing join ----

    @Test
    void detectsCloseWithoutJoin() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordScopeClosed("s", owner); // never joined — violation

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getMissingJoinIssues().size());
        assertTrue(report.getMissingJoinIssues().get(0).contains("without ever calling join()"));
    }

    @Test
    void noMissingJoin_whenNoForks() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordScopeClosed("s", owner); // empty scope, nothing to join

        assertTrue(detector.analyze().getMissingJoinIssues().isEmpty());
    }

    // ---- Unknown scope id is ignored ----

    @Test
    void ignoresEventsForUnopenedScope() {
        detector.recordFork("ghost", "a", Thread.currentThread());
        detector.recordJoin("ghost", Thread.currentThread());
        assertFalse(detector.analyze().hasIssues());
    }

    // ---- Null safety ----

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.recordScopeOpened(null, Thread.currentThread());
            detector.recordScopeOpened("s", null);
            detector.recordFork(null, "a", Thread.currentThread());
            detector.recordFork("s", null, Thread.currentThread());
            detector.recordJoin("s", null);
            detector.recordResultRead("s", "a", null);
            detector.recordScopeClosed(null, null);
        });
    }

    // ---- toString ----

    @Test
    void toString_isClean_whenNoIssues() {
        assertTrue(detector.analyze().toString().contains("No StructuredTaskScope misuse"));
    }

    @Test
    void toString_containsLearningContent_whenIssuesFound() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordJoin("s", owner);
        detector.recordFork("s", "late", owner);

        String str = detector.analyze().toString();
        assertTrue(str.contains("LEARNING"), str);
        assertTrue(str.contains("StructuredTaskScope"), str);
        assertTrue(str.contains("CRITICAL"), str);
    }

    // ---- JDK 26 sixth preview: join timeout (Joiner.onTimeout) ----

    @Test
    void detectsResultReadAfterJoinTimeout() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordJoinTimeout("s", owner);          // join() hit its deadline
        detector.recordResultRead("s", "a", owner);      // BUG: subtask was cancelled

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getResultAfterTimeoutIssues().size());
        String issue = report.getResultAfterTimeoutIssues().get(0);
        assertTrue(issue.contains("timed out"), issue);
        assertTrue(issue.contains("SUCCESS"), issue);
        assertTrue(report.getResultBeforeJoinIssues().isEmpty(),
                "timeout reads must not double-report as before-join reads");
    }

    @Test
    void joinTimeout_countsAsJoin_forMissingJoinAndForkAfterJoin() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordJoinTimeout("s", owner);
        detector.recordFork("s", "late", owner);         // fork after timed-out join
        detector.recordScopeClosed("s", owner);

        var report = detector.analyze();
        assertEquals(1, report.getForkAfterJoinIssues().size());
        assertTrue(report.getMissingJoinIssues().isEmpty(),
                "a timed-out join is still a join — no missing-join report");
    }

    @Test
    void joinTimeoutFromNonOwner_isConfinementViolation() throws Exception {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);

        Thread intruder = new Thread(() ->
                detector.recordJoinTimeout("s", Thread.currentThread()), "intruder");
        intruder.start();
        intruder.join();

        var report = detector.analyze();
        assertFalse(report.getConfinementIssues().isEmpty());
        assertTrue(report.getConfinementIssues().get(0).contains("intruder"));
    }

    @Test
    void timeoutSwallowed_withCancelledSubtasks_isWarned() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordFork("s", "a", owner);
        detector.recordFork("s", "b", owner);
        detector.recordTimeoutSwallowed("s", owner);     // onTimeout() returned a fallback

        var report = detector.analyze();
        assertEquals(1, report.getTimeoutSwallowedWarnings().size());
        String warning = report.getTimeoutSwallowedWarnings().get(0);
        assertTrue(warning.contains("2 forked subtask(s)"), warning);
        assertFalse(report.hasIssues(),
                "a swallowed timeout alone is a warning, not a correctness issue");
        assertTrue(report.toString().contains("LOW"));
    }

    @Test
    void timeoutSwallowed_withoutForks_isSilent() {
        Thread owner = Thread.currentThread();
        detector.recordScopeOpened("s", owner);
        detector.recordTimeoutSwallowed("s", owner);

        var report = detector.analyze();
        assertTrue(report.getTimeoutSwallowedWarnings().isEmpty());
        assertFalse(report.hasIssues());
    }

    @Test
    void timeoutRecords_tolerateNullsAndUnknownScopes() {
        assertDoesNotThrow(() -> {
            detector.recordJoinTimeout(null, Thread.currentThread());
            detector.recordJoinTimeout("unknown", null);
            detector.recordJoinTimeout("never-opened", Thread.currentThread());
            detector.recordTimeoutSwallowed(null, Thread.currentThread());
            detector.recordTimeoutSwallowed("never-opened", Thread.currentThread());
        });
        assertFalse(detector.analyze().hasIssues());
    }
}
