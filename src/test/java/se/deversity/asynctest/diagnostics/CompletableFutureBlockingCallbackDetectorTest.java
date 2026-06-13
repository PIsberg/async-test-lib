package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletableFutureBlockingCallbackDetectorTest {

    @Test
    void cleanWhenNoBlocking() {
        var d = new CompletableFutureBlockingCallbackDetector();
        d.recordEnterCallback("thenApply", Thread.currentThread());
        d.recordExitCallback(Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void violationWhenBlockingCallRegisteredInCallback() {
        var d = new CompletableFutureBlockingCallbackDetector();
        d.recordEnterCallback("thenApply", Thread.currentThread());
        d.recordBlockingCall(Thread.currentThread(), "CompletableFuture.get");
        d.recordExitCallback(Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("thenApply"));
        assertTrue(msg.contains("CompletableFuture.get"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("CompletableFutureBlockingCallback", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }
}
