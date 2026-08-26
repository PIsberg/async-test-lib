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

    /**
     * The count in a finding must be the number of threads that shared a round, not the number
     * of body executions. Before {@code markInvocationStart} folded rounds, {@code threads = 8,
     * invocations = 20} on the default virtual-thread runner reported 160 threads, because every
     * body execution gets a fresh virtual thread with a fresh id. See issue #349.
     */
    @Test
    void threadsAreCountedPerRoundNotAcrossTheWholeRun() throws InterruptedException {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> tl = new ThreadLocal<>();
        monitor.recordThreadLocalInit(tl, "REQUEST_USER");

        // Five rounds of two fresh threads each: ten distinct thread ids, two per round.
        for (int round = 0; round < 5; round++) {
            monitor.markInvocationStart();
            Thread first  = new Thread(() -> monitor.recordThreadLocalAccess(tl));
            Thread second = new Thread(() -> monitor.recordThreadLocalAccess(tl));
            first.start();
            second.start();
            first.join();
            second.join();
        }

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();
        String text = report.toString();

        assertTrue(text.contains("2 thread(s)"),
                "the widest round had two threads; the finding must say two, not ten. Report:\n" + text);
        assertFalse(text.contains("10 thread(s)"),
                "counting ids across rounds reports body executions as threads. Report:\n" + text);
    }

    /**
     * The evidence line must not claim thread reuse. Under the default
     * {@code useVirtualThreads = true} runner each body execution gets its own virtual thread,
     * whose ThreadLocal map dies with it, so "value crossed N reused thread(s)" described a
     * mechanism that did not happen. See issue #349.
     */
    @Test
    void theLeakLineDoesNotClaimThreadsWereReused() throws InterruptedException {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> tl = new ThreadLocal<>();
        monitor.recordThreadLocalInit(tl, "REQUEST_USER");
        Thread other = new Thread(() -> monitor.recordThreadLocalAccess(tl));
        other.start();
        other.join();

        String text = monitor.analyzeThreadLocalLeaks().toString();

        assertFalse(text.contains("reused"),
                "nothing observed here was reused; the finding is the missing remove(). Report:\n" + text);
        assertTrue(text.contains("no matching remove()"),
                "the line must argue from the missing remove(), which is the actual evidence. "
                        + "Report:\n" + text);
    }

    @Test
    void analyze_delegatesToAnalyzeThreadLocalLeaks() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> threadLocal = new ThreadLocal<>();
        monitor.recordThreadLocalInit(threadLocal, "leaked");

        ThreadLocalMonitor.ThreadLocalReport viaAnalyze = monitor.analyze();
        ThreadLocalMonitor.ThreadLocalReport viaAnalyzeThreadLocalLeaks = monitor.analyzeThreadLocalLeaks();

        assertEquals(viaAnalyzeThreadLocalLeaks.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeThreadLocalLeaks.toString(), viaAnalyze.toString());
    }
}
