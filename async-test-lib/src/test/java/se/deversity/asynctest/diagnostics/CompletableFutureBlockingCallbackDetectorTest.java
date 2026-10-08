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
    void aBlockingCallInTheOuterCallbackAfterANestedOneReturnsIsReportedAgainstTheOuter() {
        // A callback that completes another future runs that future's dependent inline; the inner
        // exit must not end the outer callback (#941).
        var d = new CompletableFutureBlockingCallbackDetector();
        Thread t = Thread.currentThread();
        d.recordEnterCallback("outer", t);
        d.recordEnterCallback("inner", t);
        d.recordExitCallback(t);
        d.recordBlockingCall(t, "CompletableFuture.join");
        d.recordExitCallback(t);

        var report = d.analyze();
        assertTrue(report.hasIssues(), "the outer callback blocked after the inner one returned");
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("'outer'"), report.violations.get(0));
    }

    @Test
    void balancedNestedCallbacksLeaveNothingBehind() {
        var d = new CompletableFutureBlockingCallbackDetector();
        Thread t = Thread.currentThread();
        d.recordEnterCallback("outer", t);
        d.recordEnterCallback("inner", t);
        d.recordExitCallback(t);
        d.recordExitCallback(t);
        d.recordBlockingCall(t, "CompletableFuture.get");
        assertFalse(d.analyze().hasIssues(), "both callbacks had returned before the blocking call");
    }

    @Test
    void aCallbackThatThrewBeforeExitDoesNotLeakIntoTheNextRound() {
        // A callback that throws before its exit leaves the slot set on a reused pool thread
        // (#941); the next round's blocking call there is outside any callback.
        var d = new CompletableFutureBlockingCallbackDetector();
        Thread t = Thread.currentThread();
        d.recordEnterCallback("thenApply", t);
        d.markInvocationStart();
        d.recordBlockingCall(t, "CompletableFuture.get");
        assertFalse(d.analyze().hasIssues(), "the callback entered last round never exited, but"
                + " this round's call is outside any callback");
    }

    @Test
    void anExitWithNoCallbackOpenIsHarmless() {
        var d = new CompletableFutureBlockingCallbackDetector();
        Thread t = Thread.currentThread();
        d.recordExitCallback(t);
        d.recordEnterCallback("thenApply", t);
        d.recordBlockingCall(t, "CompletableFuture.get");
        assertTrue(d.analyze().hasIssues());
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
