package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletableFutureCombinatorMisuseDetectorTest {

    private static Thread here() { return Thread.currentThread(); }

    @Test
    void cleanWhenNothingRecorded() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("COMPLETABLE FUTURE COMBINATOR MISUSE - clean", d.analyze().toString());
    }

    /**
     * The correctly written twin: every constituent completes, then the combined future is
     * joined. Nothing is proceeded past early, so the detector stays silent.
     */
    @Test
    void allOfJoinedAfterEveryConstituentStaysSilent() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();

        d.recordCombinator(all, "writes", "allOf", 2, here());
        d.recordConstituentCompleted(all, "a", false, here());
        d.recordConstituentCompleted(all, "b", false, here());
        d.recordAwait(all, "join", here());

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void unawaitedCombinatorWithOutstandingConstituentsIsHigh() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();

        d.recordCombinator(all, "writes", "allOf", 3, here());
        d.recordConstituentCompleted(all, "a", false, here());
        // b and c never finish, and nobody joins

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("writes"));
        assertTrue(msg.contains("never awaited"));
        assertTrue(msg.contains("2 future(s) are still running"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertEquals("CompletableFutureCombinatorMisuse", report.structuredViolations.get(0).detector());
    }

    /**
     * Nobody joined, but every constituent finished anyway, so this run lost nothing. Reporting it
     * would be a false positive on code that happens to be correct in practice.
     */
    @Test
    void unawaitedCombinatorWhoseConstituentsAllFinishedIsSilent() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();

        d.recordCombinator(all, "writes", "allOf", 2, here());
        d.recordConstituentCompleted(all, "a", false, here());
        d.recordConstituentCompleted(all, "b", false, here());

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nonBlockingReadBeforeCompletionIsHigh() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();

        d.recordCombinator(all, "writes", "allOf", 2, here());
        d.recordConstituentCompleted(all, "a", false, here());
        d.recordAwait(all, "getNow", here());              // read while b is still running
        d.recordConstituentCompleted(all, "b", false, here());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("read with getNow()"));
        assertTrue(msg.contains("only 1 of 2"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void nonBlockingReadAfterCompletionIsSilent() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();

        d.recordCombinator(all, "writes", "allOf", 2, here());
        d.recordConstituentCompleted(all, "a", false, here());
        d.recordConstituentCompleted(all, "b", false, here());
        d.recordAwait(all, "getNow", here());              // everything was already in

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void blockingJoinIsNeverFlaggedAsAnEarlyRead() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();

        d.recordCombinator(all, "writes", "allOf", 2, here());
        d.recordAwait(all, "join", here());                // join waits, so order does not matter
        d.recordConstituentCompleted(all, "a", false, here());
        d.recordConstituentCompleted(all, "b", false, here());

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void anyOfLoserFailureAfterTheReadIsMedium() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var any = new CompletableFuture<Object>();

        d.recordCombinator(any, "first-answer", "anyOf", 2, here());
        d.recordConstituentCompleted(any, "fast", false, here());
        d.recordAwait(any, "join", here());
        d.recordConstituentCompleted(any, "slow", true, here());   // fails with nowhere to go

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("slow"));
        assertTrue(msg.contains("reach no handler"));
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
    }

    @Test
    void anyOfLoserSucceedingAfterTheReadIsSilent() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var any = new CompletableFuture<Object>();

        d.recordCombinator(any, "first-answer", "anyOf", 2, here());
        d.recordConstituentCompleted(any, "fast", false, here());
        d.recordAwait(any, "join", here());
        d.recordConstituentCompleted(any, "slow", false, here());  // just late, not failed

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void eventsForAnUnregisteredCombinatorAreIgnored() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var stray = new CompletableFuture<Void>();
        d.recordConstituentCompleted(stray, "a", true, here());
        d.recordAwait(stray, "getNow", here());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullsAreIgnored() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();
        d.recordCombinator(null, "x", "allOf", 2, here());
        d.recordCombinator(all, "x", "allOf", 2, null);
        d.recordConstituentCompleted(null, "a", false, here());
        d.recordAwait(null, "join", here());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void unknownArityProducesNoFinding() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();
        d.recordCombinator(all, "writes", "allOf", 0, here());
        assertFalse(d.analyze().hasIssues(), "without an arity there is nothing to compare against");
    }

    @Test
    void disableStopsRecording() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();
        d.disable();
        d.recordCombinator(all, "off", "allOf", 2, here());
        assertFalse(d.analyze().hasIssues());

        d.enable();
        var other = new CompletableFuture<Void>();
        d.recordCombinator(other, "on", "allOf", 2, here());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void reportToStringCarriesTheFinding() {
        var d = new CompletableFutureCombinatorMisuseDetector();
        var all = new CompletableFuture<Void>();
        d.recordCombinator(all, "writes", "allOf", 2, here());
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("COMPLETABLE FUTURE COMBINATOR MISUSE DETECTED"));
        assertTrue(rendered.contains("writes"));
        assertTrue(rendered.contains("Fix:"));
    }
}
