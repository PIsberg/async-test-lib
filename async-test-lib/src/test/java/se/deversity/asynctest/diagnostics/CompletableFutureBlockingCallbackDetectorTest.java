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

    @Test
    void nullThreadIsIgnoredForAllRecordMethods() {
        var d = new CompletableFutureBlockingCallbackDetector();
        d.recordEnterCallback("thenApply", null);
        d.recordBlockingCall(null, "CompletableFuture.get");
        d.recordExitCallback(null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void blockingCallOutsideCallbackIsIgnored() {
        var d = new CompletableFutureBlockingCallbackDetector();
        d.recordBlockingCall(Thread.currentThread(), "CompletableFuture.get");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void reportToStringReflectsState() {
        var clean = new CompletableFutureBlockingCallbackDetector().analyze();
        assertEquals("COMPLETABLE FUTURE BLOCKING CALLBACK — clean", clean.toString());

        var d = new CompletableFutureBlockingCallbackDetector();
        d.recordEnterCallback("thenApply", Thread.currentThread());
        d.recordBlockingCall(Thread.currentThread(), "CompletableFuture.get");
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("COMPLETABLE FUTURE BLOCKING CALLBACK DETECTED"));
        assertTrue(rendered.contains("thenApply"));
    }
}
