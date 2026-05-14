package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ThreadLocalMonitor.
 */
public class ThreadLocalMonitorTest {

    @Test
    void noUsageReturnsNoIssues() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No ThreadLocal usage — should report no issues");
        assertTrue(report.uncleanedThreadLocals.isEmpty());
        assertTrue(report.likelyLeaks.isEmpty());
    }

    @Test
    void initAndCleanupNoLeak() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> tl = new ThreadLocal<>();

        monitor.recordThreadLocalInit(tl, "my-context");
        monitor.recordThreadLocalCleanup(tl);

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();

        assertFalse(report.hasIssues(), "Init followed by cleanup should not produce a leak");
        assertTrue(report.uncleanedThreadLocals.isEmpty(),
                "uncleanedThreadLocals should be empty after proper cleanup");
    }

    @Test
    void initWithoutCleanupDetectedAsLeak() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> tl = new ThreadLocal<>();

        monitor.recordThreadLocalInit(tl, "leaked-context");
        // deliberately no cleanup

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();

        assertTrue(report.hasIssues(), "Init without cleanup should be reported as an issue");
        assertFalse(report.uncleanedThreadLocals.isEmpty(),
                "uncleanedThreadLocals should contain the leaked ThreadLocal");
    }

    @Test
    void multipleThreadsWithoutCleanupIsLikelyLeak() throws InterruptedException {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> tl = new ThreadLocal<>();

        Thread t1 = new Thread(() -> monitor.recordThreadLocalInit(tl, "shared-tl"));
        Thread t2 = new Thread(() -> monitor.recordThreadLocalAccess(tl));

        t1.start();
        t1.join();
        t2.start();
        t2.join();

        // no cleanup performed by either thread

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();

        assertTrue(report.hasIssues());
        assertFalse(report.likelyLeaks.isEmpty(),
                "ThreadLocal accessed by 2+ threads without cleanup should appear in likelyLeaks");
    }

    @Test
    void nullThreadLocalHandledGracefully() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();

        assertDoesNotThrow(() -> monitor.recordThreadLocalInit(null, "null-tl"));
        assertDoesNotThrow(() -> monitor.recordThreadLocalAccess(null));
        assertDoesNotThrow(() -> monitor.recordThreadLocalCleanup(null));

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Null ThreadLocal should be silently ignored");
    }

    @Test
    void disabledDetectorSkipsRecording() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        monitor.disable();

        ThreadLocal<String> tl = new ThreadLocal<>();
        monitor.recordThreadLocalInit(tl, "disabled-tl");
        // no cleanup — but detector is disabled so nothing should be recorded

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();
        assertFalse(report.hasIssues(), "Disabled monitor must not record any usage");
    }

    @Test
    void reportToStringContainsLeakInfo() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> tl = new ThreadLocal<>();

        monitor.recordThreadLocalInit(tl, "ctx-tl");
        // no cleanup

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("THREADLOCAL LEAK"), "toString() should describe the leak");
        assertTrue(text.contains("ctx-tl"), "toString() should name the offending ThreadLocal");
    }

    @Test
    void resetClearsState() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> tl = new ThreadLocal<>();

        monitor.recordThreadLocalInit(tl, "temp-tl");

        monitor.reset();

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();
        assertFalse(report.hasIssues(), "After reset() all state should be cleared");
    }
}
