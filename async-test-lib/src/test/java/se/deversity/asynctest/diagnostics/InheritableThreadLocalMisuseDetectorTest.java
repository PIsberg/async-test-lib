package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InheritableThreadLocalMisuseDetectorTest {

    static final InheritableThreadLocal<String> USER_CONTEXT = new InheritableThreadLocal<>();

    @Test
    void testNoIssuesFromNonPoolThread() {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();
        USER_CONTEXT.set("user-1");
        detector.recordGet(USER_CONTEXT, "USER_CONTEXT");
        detector.recordSet(USER_CONTEXT, "USER_CONTEXT", "user-1");

        // Current thread is not registered as a pool thread, so no issues
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testDetectsGetFromPooledThread() throws InterruptedException {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();

        Thread poolThread = new Thread(() -> {
            detector.registerPoolThread(Thread.currentThread());
            USER_CONTEXT.set("leaked-value");
            detector.recordGet(USER_CONTEXT, "USER_CONTEXT");
        });
        poolThread.start();
        poolThread.join();

        InheritableThreadLocalMisuseDetector.InheritableThreadLocalReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect ITL get from pooled thread");
        assertFalse(report.pooledGetIssues.isEmpty());
        assertTrue(report.pooledGetIssues.get(0).contains("USER_CONTEXT"));
        assertTrue(report.pooledGetIssues.get(0).contains("thread-creation time"));
    }

    @Test
    void testDetectsSetFromPooledThread() throws InterruptedException {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();

        Thread poolThread = new Thread(() -> {
            detector.registerPoolThread(Thread.currentThread());
            detector.recordSet(USER_CONTEXT, "USER_CONTEXT", "task-value");
        });
        poolThread.start();
        poolThread.join();

        InheritableThreadLocalMisuseDetector.InheritableThreadLocalReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect ITL set from pooled thread");
        assertFalse(report.pooledSetIssues.isEmpty());
        assertTrue(report.pooledSetIssues.get(0).contains("contamination"));
    }

    @Test
    void inheritanceWorkingAsDesignedIsNotAFinding() throws InterruptedException {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();

        // Four threads read one InheritableThreadLocal, and nobody declared a pooled thread.
        // Several threads seeing the value is what InheritableThreadLocal is FOR - the value is
        // inherited by children by design - so this is the mechanism working, not a defect. The
        // two findings that are grounded in evidence, pooledGet and pooledSet, need the caller
        // to have said which threads are pooled, and none did here (#517).
        Thread[] readers = new Thread[4];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> detector.recordGet(USER_CONTEXT, "REQUEST_ID"));
            readers[i].start();
        }
        for (Thread r : readers) {
            r.join();
        }

        InheritableThreadLocalMisuseDetector.InheritableThreadLocalReport report = detector.analyze();
        assertFalse(report.hasIssues(),
                "inheritance across threads is the point of an InheritableThreadLocal, so it "
                        + "cannot on its own be misuse: " + report);
    }

    @Test
    void multiThreadAccessIsReportedAsContextRatherThanAsAFinding() throws InterruptedException {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();

        Thread t1 = new Thread(() -> detector.recordGet(USER_CONTEXT, "SHARED_VAR"));
        Thread t2 = new Thread(() -> detector.recordGet(USER_CONTEXT, "SHARED_VAR"));
        t1.start(); t2.start();
        t1.join();  t2.join();

        InheritableThreadLocalMisuseDetector.InheritableThreadLocalReport report = detector.analyze();
        assertFalse(report.hasIssues(),
                "two threads reading an InheritableThreadLocal is inheritance doing its job, so "
                        + "it is shown but not counted as misuse (#517): " + report);
        assertTrue(report.threadActivity.containsKey("SHARED_VAR"),
                "the count is still reported, as context: " + report.threadActivity);
        assertTrue(report.threadActivity.get("SHARED_VAR").contains("2 threads"));
    }

    @Test
    void testSingleThreadAccessIsNotEvenReportedAsActivity() {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();
        detector.recordGet(USER_CONTEXT, "SINGLE_VAR");
        detector.recordGet(USER_CONTEXT, "SINGLE_VAR");

        // Same thread, two reads — nothing to say at all
        InheritableThreadLocalMisuseDetector.InheritableThreadLocalReport report = detector.analyze();
        assertTrue(report.threadActivity.isEmpty(),
                "one thread is not multi-thread access, so there is not even activity to show");
        assertFalse(report.hasIssues());
    }

    @Test
    void testNullSafety() {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();
        assertDoesNotThrow(() -> {
            detector.registerPoolThread(null);
            detector.recordGet(null, "x");
            detector.recordSet(null, "x", "v");
        });
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testAutoNameFromIdentityHash() throws InterruptedException {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();
        InheritableThreadLocal<String> unnamed = new InheritableThreadLocal<>();

        Thread poolThread = new Thread(() -> {
            detector.registerPoolThread(Thread.currentThread());
            detector.recordGet(unnamed, null); // no name
        });
        poolThread.start();
        poolThread.join();

        InheritableThreadLocalMisuseDetector.InheritableThreadLocalReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.pooledGetIssues.get(0).contains("itl@"));
    }

    @Test
    void testReportToStringContainsFixHint() throws InterruptedException {
        InheritableThreadLocalMisuseDetector detector = new InheritableThreadLocalMisuseDetector();

        Thread poolThread = new Thread(() -> {
            detector.registerPoolThread(Thread.currentThread());
            detector.recordGet(USER_CONTEXT, "CTX");
        });
        poolThread.start();
        poolThread.join();

        String str = detector.analyze().toString();
        assertTrue(str.contains("INHERITABLE THREAD LOCAL MISUSE DETECTED"));
        assertTrue(str.contains("Fix"));
        assertTrue(str.contains("ScopedValue"));
    }
}
