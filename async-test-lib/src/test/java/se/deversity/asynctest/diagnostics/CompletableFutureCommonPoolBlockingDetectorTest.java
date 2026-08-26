package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

public class CompletableFutureCommonPoolBlockingDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenFutureNotRegistered() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<String> cf = new CompletableFuture<>();
        // future not registered as common-pool — blocking not tracked
        d.recordBlockingCall(cf, Thread.currentThread(), "Thread.sleep");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsBlockingInCommonPoolFuture() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<String> cf = new CompletableFuture<>();
        Thread t = Thread.currentThread();
        d.recordCommonPoolSubmission(cf, t, "fetchData");
        d.recordBlockingCall(cf, t, "JDBC query");
        assertTrue(d.analyze().hasIssues());
        String msg = d.analyze().violations.get(0);
        assertTrue(msg.contains("fetchData"));
        assertTrue(msg.contains("JDBC query"));
    }

    @Test
    void testDetectsMultipleBlockingCalls() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<Void> cf = new CompletableFuture<>();
        Thread t = Thread.currentThread();
        d.recordCommonPoolSubmission(cf, t, "task");
        d.recordBlockingCall(cf, t, "Thread.sleep");
        d.recordBlockingCall(cf, t, "InputStream.read");
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testNoIssueWhenDedicatedExecutorUsed() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<String> cf = new CompletableFuture<>();
        Thread t = Thread.currentThread();
        // cf is NOT registered as common-pool (custom executor was provided)
        d.recordBlockingCall(cf, t, "JDBC query");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testAutoLabelWhenNoNameProvided() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<String> cf = new CompletableFuture<>();
        Thread t = Thread.currentThread();
        d.recordCommonPoolSubmission(cf, t, null);
        d.recordBlockingCall(cf, t, "IO");
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("task@"));
    }

    @Test
    void testNullSafety() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        assertDoesNotThrow(() -> {
            d.recordCommonPoolSubmission(null, Thread.currentThread(), "x");
            d.recordBlockingCall(null, Thread.currentThread(), "IO");
            d.recordBlockingCall(new CompletableFuture<>(), null, "IO");
        });
        assertFalse(d.analyze().hasIssues());
    }

    /**
     * The same blocking call, made 400 times, is one finding with a count — not 400 lines.
     * This library runs inside somebody else's test suite, so its output is somebody else's
     * build log. See issue #351.
     */
    @Test
    void identicalBlockingCallsCollapseToOneCountedFinding() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<String> cf = new CompletableFuture<>();
        Thread t = Thread.currentThread();
        d.recordCommonPoolSubmission(cf, t, "fetchData");
        for (int i = 0; i < 400; i++) {
            d.recordBlockingCall(cf, t, "Future.get");
        }

        var report = d.analyze();
        assertEquals(1, report.violations.size(),
                "400 identical calls must not produce 400 report lines: " + report.violations.size());
        assertTrue(report.violations.get(0).contains("(x400)"),
                "the collapsed line must still say how often it happened: " + report.violations.get(0));
    }

    /** Distinct findings are kept apart; only identical text collapses. */
    @Test
    void distinctFindingsAreNotCollapsed() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<String> cf = new CompletableFuture<>();
        Thread t = Thread.currentThread();
        d.recordCommonPoolSubmission(cf, t, "fetchData");
        d.recordBlockingCall(cf, t, "Future.get");
        d.recordBlockingCall(cf, t, "Thread.sleep");

        assertEquals(2, d.analyze().violations.size());
    }

    /**
     * Unbounded accumulation on the recording path is the other half of #351: a consumer
     * stress-testing a hot path can produce millions of distinct findings, and nothing capped
     * them. Past the cap the detector counts what it drops and says so.
     */
    @Test
    void distinctFindingsAreBoundedAndTheDropIsReported() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<String> cf = new CompletableFuture<>();
        Thread t = Thread.currentThread();
        d.recordCommonPoolSubmission(cf, t, "fetchData");
        int attempts = CompletableFutureCommonPoolBlockingDetector.MAX_DISTINCT_FINDINGS + 50;
        for (int i = 0; i < attempts; i++) {
            d.recordBlockingCall(cf, t, "call-" + i);
        }

        var report = d.analyze();
        assertEquals(CompletableFutureCommonPoolBlockingDetector.MAX_DISTINCT_FINDINGS,
                report.violations.size(),
                "the report must stop at the cap rather than growing without bound");
        assertTrue(report.toString().contains("50 further distinct finding(s) not shown"),
                "a silent truncation reads as 'that was everything'. Report:\n" + report);
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new CompletableFutureCommonPoolBlockingDetector();
        CompletableFuture<String> cf = new CompletableFuture<>();
        Thread t = Thread.currentThread();
        d.recordCommonPoolSubmission(cf, t, "task");
        d.recordBlockingCall(cf, t, "sleep");
        String s = d.analyze().toString();
        assertTrue(s.contains("COMPLETABLEFUTURE COMMON POOL BLOCKING"));
        assertTrue(s.contains("Fix"));
        assertTrue(s.contains("Executor"));
    }
}
