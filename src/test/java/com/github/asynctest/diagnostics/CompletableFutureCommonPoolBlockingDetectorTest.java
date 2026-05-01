package com.github.asynctest.diagnostics;

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
