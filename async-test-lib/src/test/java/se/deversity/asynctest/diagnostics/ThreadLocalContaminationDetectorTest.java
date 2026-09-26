package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ThreadLocalContaminationDetectorTest {

    private final ThreadLocal<String> TL  = new ThreadLocal<>();
    private final ThreadLocal<String> TL2 = new ThreadLocal<>();

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new ThreadLocalContaminationDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssuesWhenSetAndGetInSameTask() {
        var d = new ThreadLocalContaminationDetector();
        Thread t = Thread.currentThread();
        d.recordNewTask(t, "task-1");
        d.recordSet(t, TL, "TL");
        d.recordGet(t, TL, "TL", true);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsContaminationFromPreviousTask() {
        var d = new ThreadLocalContaminationDetector();
        Thread t = Thread.currentThread();
        d.recordNewTask(t, "task-1");
        d.recordSet(t, TL, "TL");
        d.recordNewTask(t, "task-2");
        d.recordGet(t, TL, "TL", true); // value set in task-1, read in task-2
        assertTrue(d.analyze().hasIssues());
        String msg = d.analyze().contaminations.get(0);
        assertTrue(msg.contains("task-2"));
        assertTrue(msg.contains("TL"));
    }

    @Test
    void testNoIssueWhenGetReturnsNull() {
        var d = new ThreadLocalContaminationDetector();
        Thread t = Thread.currentThread();
        d.recordNewTask(t, "task-1");
        d.recordSet(t, TL, "TL");
        d.recordNewTask(t, "task-2");
        d.recordGet(t, TL, "TL", false); // TL was cleared — no contamination
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenResetInNewTask() {
        var d = new ThreadLocalContaminationDetector();
        Thread t = Thread.currentThread();
        d.recordNewTask(t, "task-1");
        d.recordSet(t, TL, "TL");
        d.recordNewTask(t, "task-2");
        d.recordSet(t, TL, "TL"); // explicitly reset in task-2 before get
        d.recordGet(t, TL, "TL", true);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsMultipleContaminatedLocals() {
        var d = new ThreadLocalContaminationDetector();
        Thread t = Thread.currentThread();
        d.recordNewTask(t, "t1");
        d.recordSet(t, TL,  "TL");
        d.recordSet(t, TL2, "TL2");
        d.recordNewTask(t, "t2");
        d.recordGet(t, TL,  "TL",  true);
        d.recordGet(t, TL2, "TL2", true);
        assertTrue(d.analyze().hasIssues());
        assertEquals(2, d.analyze().contaminations.size());
    }

    @Test
    void testNullSafety() {
        var d = new ThreadLocalContaminationDetector();
        assertDoesNotThrow(() -> {
            d.recordNewTask(null, "x");
            d.recordSet(null, TL, "TL");
            d.recordSet(Thread.currentThread(), null, "TL");
            d.recordGet(null, TL, "TL", true);
            d.recordGet(Thread.currentThread(), null, "TL", true);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new ThreadLocalContaminationDetector();
        Thread t = Thread.currentThread();
        d.recordNewTask(t, "t1");
        d.recordSet(t, TL, "TL");
        d.recordNewTask(t, "t2");
        d.recordGet(t, TL, "TL", true);
        String s = d.analyze().toString();
        assertTrue(s.contains("THREADLOCAL CONTAMINATION"));
        assertTrue(s.contains("Fix"));
    }

    /**
     * A default virtual thread has no name, so the contaminated thread printed as "Thread ''"
     * (#790). It is named by its id instead.
     */
    @Test
    void anUnnamedVirtualThreadIsReportedByIdNotByAnEmptyName() {
        var d = new ThreadLocalContaminationDetector();
        Thread vt = Thread.ofVirtual().unstarted(() -> { });
        assertEquals("", vt.getName(), "precondition: a default virtual thread has no name");
        d.recordNewTask(vt, "task-1");
        d.recordSet(vt, TL, "TL");
        d.recordNewTask(vt, "task-2");
        d.recordGet(vt, TL, "TL", true);
        String msg = d.analyze().contaminations.get(0);
        assertFalse(msg.contains("Thread ''"), "the thread must not print as '': " + msg);
        assertTrue(msg.contains("Thread '#" + vt.threadId() + "'"),
                "the unnamed thread must be named by its id: " + msg);
    }
}
