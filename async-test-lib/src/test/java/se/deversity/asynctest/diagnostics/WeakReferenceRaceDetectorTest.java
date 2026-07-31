package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import static org.junit.jupiter.api.Assertions.*;

public class WeakReferenceRaceDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new WeakReferenceRaceDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenAllNonNull() throws Exception {
        var d = new WeakReferenceRaceDetector();
        Object obj = new Object();
        WeakReference<Object> ref = new WeakReference<>(obj);
        d.recordGet(ref, "ref", obj, Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordGet(ref, "ref", obj, Thread.currentThread()));
        t2.start();
        t2.join();
        assertFalse(d.analyze().hasIssues());
        // keep strong reference alive
        assertNotNull(obj);
    }

    @Test
    void testWarnsWhenReferentCollectedMidTest() throws Exception {
        var d = new WeakReferenceRaceDetector();
        Object obj = new Object();
        WeakReference<Object> ref = new WeakReference<>(obj);
        // Thread A gets non-null
        d.recordGet(ref, "ref", obj, Thread.currentThread());
        // Thread B gets null (simulate GC collection)
        Thread t2 = new Thread(() -> d.recordGet(ref, "ref", null, Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertFalse(d.analyze().violations.isEmpty() && d.analyze().warnings.isEmpty());
    }

    @Test
    void testReportsNullDereference() throws Exception {
        var d = new WeakReferenceRaceDetector();
        WeakReference<Object> ref = new WeakReference<>(new Object());
        d.recordNullDereference(ref, "ref", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordNullDereference(ref, "ref", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertFalse(d.analyze().violations.isEmpty());
        assertTrue(d.analyze().violations.get(0).contains("null check"));
    }

    @Test
    void testNullDereferenceTakesPriorityOverWarning() {
        var d = new WeakReferenceRaceDetector();
        Object obj = new Object();
        WeakReference<Object> ref = new WeakReference<>(obj);
        d.recordGet(ref, "ref", obj, Thread.currentThread());
        d.recordGet(ref, "ref", null, Thread.currentThread());
        d.recordNullDereference(ref, "ref", Thread.currentThread());
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.violations.isEmpty());
    }

    @Test
    void testNullSafety() {
        var d = new WeakReferenceRaceDetector();
        WeakReference<Object> ref = new WeakReference<>(new Object());
        assertDoesNotThrow(() -> {
            d.recordGet(null, "x", new Object(), Thread.currentThread());
            d.recordGet(ref, "x", new Object(), null);
            d.recordNullDereference(null, "x", Thread.currentThread());
            d.recordNullDereference(ref, "x", null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new WeakReferenceRaceDetector();
        WeakReference<Object> ref = new WeakReference<>(new Object());
        d.recordNullDereference(ref, "ref", Thread.currentThread());
        String s = d.analyze().toString();
        assertTrue(s.contains("WEAK REFERENCE RACE"));
        assertTrue(s.contains("Fix"));
    }

    @Test
    void testWarningToStringShowsThreadNames() throws Exception {
        var d = new WeakReferenceRaceDetector();
        Object obj = new Object();
        WeakReference<Object> ref = new WeakReference<>(obj);
        d.recordGet(ref, "myRef", obj, Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordGet(ref, "myRef", null, Thread.currentThread()), "gc-sim-thread");
        t2.start();
        t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("myRef"));
    }
}
