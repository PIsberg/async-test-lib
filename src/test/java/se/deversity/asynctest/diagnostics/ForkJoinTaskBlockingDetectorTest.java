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
