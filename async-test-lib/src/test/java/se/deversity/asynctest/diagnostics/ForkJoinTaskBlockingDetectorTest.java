package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ForkJoinTaskBlockingDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new ForkJoinTaskBlockingDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenBlockingOutsideForkJoinTask() {
        var d = new ForkJoinTaskBlockingDetector();
        // thread never registered as inside a ForkJoinTask
        d.recordBlockingCallAttempted(Thread.currentThread(), "Thread.sleep");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsBlockingInsideForkJoinTask() {
        var d = new ForkJoinTaskBlockingDetector();
        Thread t = Thread.currentThread();
        d.recordForkJoinTaskEntered(t);
        d.recordBlockingCallAttempted(t, "Thread.sleep");
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().blockingCalls.get(0).contains("Thread.sleep"));
    }

    @Test
    void testNoIssueAfterTaskExited() {
        var d = new ForkJoinTaskBlockingDetector();
        Thread t = Thread.currentThread();
        d.recordForkJoinTaskEntered(t);
        d.recordForkJoinTaskExited(t);
        d.recordBlockingCallAttempted(t, "Thread.sleep"); // task already finished
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void blockingInTheParentAfterAnInlinedChildReturnsIsStillReported() {
        // A parent's join() often runs the child on the same worker: enter, enter, exit, and the
        // parent is still inside its task (#940).
        var d = new ForkJoinTaskBlockingDetector();
        Thread t = Thread.currentThread();
        d.recordForkJoinTaskEntered(t);
        d.recordForkJoinTaskEntered(t);
        d.recordForkJoinTaskExited(t);
        d.recordBlockingCallAttempted(t, "Future.get");
        assertTrue(d.analyze().hasIssues(), "the parent task blocked after its child returned");
        d.recordForkJoinTaskExited(t);
    }

    @Test
    void balancedNestedTasksLeaveNothingBehind() {
        var d = new ForkJoinTaskBlockingDetector();
        Thread t = Thread.currentThread();
        d.recordForkJoinTaskEntered(t);
        d.recordForkJoinTaskEntered(t);
        d.recordForkJoinTaskExited(t);
        d.recordForkJoinTaskExited(t);
        d.recordBlockingCallAttempted(t, "Thread.sleep");
        assertFalse(d.analyze().hasIssues(), "both tasks had ended before the blocking call");
    }

    @Test
    void aTaskThatThrewBeforeExitDoesNotLeakIntoTheNextRound() {
        // A body that throws between enter and exit leaves the thread marked; a pooled worker
        // reused in the next round must not be reported for blocking outside any task (#940).
        var d = new ForkJoinTaskBlockingDetector();
        Thread t = Thread.currentThread();
        d.recordForkJoinTaskEntered(t);
        d.markInvocationStart();
        d.recordBlockingCallAttempted(t, "Thread.sleep");
        assertFalse(d.analyze().hasIssues(), "the task entered last round never exited, but this"
                + " round's call is outside any task");
    }

    @Test
    void testDetectsMultipleBlockingCalls() {
        var d = new ForkJoinTaskBlockingDetector();
        Thread t = Thread.currentThread();
        d.recordForkJoinTaskEntered(t);
        d.recordBlockingCallAttempted(t, "Thread.sleep");
        d.recordBlockingCallAttempted(t, "Future.get");
        d.recordBlockingCallAttempted(t, "InputStream.read");
        assertEquals(3, d.analyze().blockingCalls.size());
    }

    @Test
    void testNullSafety() {
        var d = new ForkJoinTaskBlockingDetector();
        assertDoesNotThrow(() -> {
            d.recordForkJoinTaskEntered(null);
            d.recordForkJoinTaskExited(null);
            d.recordBlockingCallAttempted(null, "Thread.sleep");
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new ForkJoinTaskBlockingDetector();
        Thread t = Thread.currentThread();
        d.recordForkJoinTaskEntered(t);
        d.recordBlockingCallAttempted(t, "Thread.sleep");
        String s = d.analyze().toString();
        assertTrue(s.contains("BLOCKING CALL INSIDE FORKJOINTASK"));
        assertTrue(s.contains("Fix"));
        assertTrue(s.contains("managedBlock"));
    }
}
