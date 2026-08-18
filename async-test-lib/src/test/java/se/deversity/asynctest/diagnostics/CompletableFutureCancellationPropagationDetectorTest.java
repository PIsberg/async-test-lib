package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletableFutureCancellationPropagationDetectorTest {

    private static Thread here() { return Thread.currentThread(); }

    @Test
    void cleanWhenNothingRecorded() {
        var d = new CompletableFutureCancellationPropagationDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("COMPLETABLE FUTURE CANCELLATION PROPAGATION - clean", d.analyze().toString());
    }

    @Test
    void workWithNoCancelIsSilent() {
        var d = new CompletableFutureCancellationPropagationDetector();
        d.recordWorkStarted("report", "fetch", here());
        d.recordWorkCompleted("report", "fetch", here());
        assertFalse(d.analyze().hasIssues());
    }

    /**
     * The correctly written twin: the stage checks for cancellation and returns before doing more
     * work, so nothing is recorded after the cancel and the detector stays silent. Cancelling with
     * {@code false} also avoids the ignored-interrupt finding.
     */
    @Test
    void cooperativeStageStaysSilent() {
        var d = new CompletableFutureCancellationPropagationDetector();
        var view = new CompletableFuture<String>();

        d.recordWorkStarted("report", "fetch", here());
        d.cancel(view, "report", "view", false);
        if (!view.isCancelled()) {              // the stage's own cooperative check
            d.recordWorkCompleted("report", "fetch", here());
        }

        assertFalse(d.analyze().hasIssues(), "a stage that abandons its work leaves nothing to report");
    }

    /**
     * The same twin at the other timing: the cancel lands before the stage body is dispatched. The
     * body still starts - cancel() dequeues nothing - records that it started, sees the
     * cancellation and returns. It did no work after the cancel, so there is nothing to report;
     * a stage that starts is not a stage that ran to the end.
     */
    @Test
    void cooperativeStageDispatchedAfterTheCancelStaysSilent() {
        var d = new CompletableFutureCancellationPropagationDetector();
        var view = new CompletableFuture<String>();

        d.cancel(view, "report", "view", false);
        d.recordWorkStarted("report", "fetch", here());   // dispatched late, as a busy pool will
        if (!view.isCancelled()) {                        // the stage's own cooperative check
            d.recordWorkCompleted("report", "fetch", here());
        }

        assertFalse(d.analyze().hasIssues(),
                "a stage that starts and immediately abandons its work did nothing after the cancel");
    }

    @Test
    void aStageThatStartsAndFinishesAfterTheCancelIsHighAndCountsBoth() {
        var d = new CompletableFutureCancellationPropagationDetector();
        var view = new CompletableFuture<String>();

        d.cancel(view, "report", "view", false);
        d.recordWorkStarted("report", "fetch", here());
        d.recordWorkCompleted("report", "fetch", here());   // ran to the end regardless

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("fetch completed"), msg);
        assertTrue(msg.contains("1 stage start(s)"), msg);
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertEquals(1, report.structuredViolations.get(0).attributes().get("eventsAfterCancel"));
        assertEquals(1, report.structuredViolations.get(0).attributes().get("startedAfterCancel"));
    }

    @Test
    void workAfterCancelIsHigh() {
        var d = new CompletableFutureCancellationPropagationDetector();
        var view = new CompletableFuture<String>();

        d.recordWorkStarted("report", "fetch", here());
        d.cancel(view, "report", "view", false);
        d.recordWorkCompleted("report", "fetch", here());   // ran on regardless

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("report"));
        assertTrue(msg.contains("fetch completed"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertEquals("CompletableFutureCancellationPropagation",
                report.structuredViolations.get(0).detector());
        assertEquals(1, report.structuredViolations.get(0).attributes().get("eventsAfterCancel"));
    }

    @Test
    void cancelWithInterruptFlagIsMedium() {
        var d = new CompletableFutureCancellationPropagationDetector();
        var view = new CompletableFuture<String>();
        d.cancel(view, "report", "view", true);

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("ignores mayInterruptIfRunning"));
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
    }

    @Test
    void bothFindingsCanFireForOnePipeline() {
        var d = new CompletableFutureCancellationPropagationDetector();
        var view = new CompletableFuture<String>();

        d.recordWorkStarted("report", "fetch", here());
        d.cancel(view, "report", "view", true);
        d.recordWorkCompleted("report", "fetch", here());

        var report = d.analyze();
        assertEquals(2, report.violations.size());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(1).severity());
    }

    @Test
    void pipelinesAreIndependent() {
        var d = new CompletableFutureCancellationPropagationDetector();
        var a = new CompletableFuture<String>();

        d.cancel(a, "cancelled-pipeline", "a", false);
        d.recordWorkCompleted("cancelled-pipeline", "a-stage", here());
        d.recordWorkCompleted("other-pipeline", "b-stage", here());   // never cancelled

        var report = d.analyze();
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("cancelled-pipeline"));
    }

    @Test
    void cancelReturnValueIsRecorded() {
        var d = new CompletableFutureCancellationPropagationDetector();
        var already = CompletableFuture.completedFuture("done");
        assertFalse(d.cancel(already, "p", "already-complete", true),
                "cancel on a completed future returns false");
        assertEquals(false, d.analyze().structuredViolations.get(0).attributes().get("cancelReturned"));
    }

    @Test
    void nullsAreIgnored() {
        var d = new CompletableFutureCancellationPropagationDetector();
        assertFalse(d.cancel(null, "p", "f", true));
        d.recordCancel("p", "f", true, true, null);
        d.recordWorkStarted("p", "s", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullLabelsFallBackToDefaults() {
        var d = new CompletableFutureCancellationPropagationDetector();
        d.recordCancel(null, null, false, true, here());
        d.recordWorkCompleted(null, null, here());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("pipeline"));
        assertTrue(report.violations.get(0).contains("stage"));
    }

    @Test
    void disableStopsRecording() {
        var d = new CompletableFutureCancellationPropagationDetector();
        d.disable();
        d.recordCancel("p", "f", true, true, here());
        d.recordWorkCompleted("p", "s", here());
        assertFalse(d.analyze().hasIssues());

        d.enable();
        d.recordCancel("q", "f", true, true, here());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void reportToStringCarriesTheFinding() {
        var d = new CompletableFutureCancellationPropagationDetector();
        d.recordCancel("report", "view", false, true, here());
        d.recordWorkCompleted("report", "fetch", here());
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("COMPLETABLE FUTURE CANCELLATION PROPAGATION DETECTED"));
        assertTrue(rendered.contains("Fix:"));
    }
}
