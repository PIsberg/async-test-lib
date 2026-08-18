package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.CompletableFutureCancellationPropagationDetector;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.example.service.ReportExporter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for ReportExporter.
 *
 * ========================================================================
 * DETECTOR: CompletableFutureCancellationPropagationDetector
 *           (DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION)
 * ========================================================================
 *
 * Future.cancel(boolean) reads like "stop the work". On a
 * CompletableFuture it does not. It completes THAT future with a
 * CancellationException and stops there: it does not reach back into the
 * stage feeding it, it cannot stop a supplier already running on a pool,
 * and the JDK documents that a CompletableFuture never interrupts
 * anything whatever mayInterruptIfRunning says.
 *
 * THE BUG:
 *   - the caller cancels the rendered view and believes the export
 *     stopped; the export writes every row anyway
 *   - cancel(true) is written in the belief that it will interrupt the
 *     stage, which on this type it never will
 *
 * THE FIX:
 *   - make the stage cooperative: poll isCancelled() (or a volatile
 *     flag) at points where abandoning the work is safe
 *   - hold the executor's own Future when a real interrupt is needed;
 *     supplyAsync gives you no handle on the running task
 *
 * WHY THE FINDING IS A FACT:
 *   the HIGH finding fires only when a stage was recorded FINISHING after
 *   a cancel on the same pipeline. The detector saw both events and their
 *   order - a cooperative stage records no completion after the cancel
 *   and is silent, whether the cancel landed during the body or before it
 *   was dispatched. A start after the cancel is counted, not reported:
 *   cancel() dequeues nothing, so a submitted body begins regardless.
 */
class ReportExporterTest {

    private static final int ROWS = 50;

    private ReportExporter exporter;
    private CompletableFutureCancellationPropagationDetector detector;

    @BeforeEach
    void setUp() {
        exporter = new ReportExporter();
        detector = new CompletableFutureCancellationPropagationDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the fixed shape. The export polls the future it feeds, so the
    // cancel actually stops the work and no stage event follows it.
    // -----------------------------------------------------------------------

    @Test
    void cooperativeExport_isClean() throws InterruptedException {
        CompletableFuture<String> view = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread stage = new Thread(() -> {
            detector.recordWorkStarted("report", "export", Thread.currentThread());
            started.countDown();
            await(cancelled);
            exporter.exportCooperatively(ROWS, view::isCancelled);
            if (!view.isCancelled()) {
                detector.recordWorkCompleted("report", "export", Thread.currentThread());
            }
            finished.countDown();
        }, "export-stage");
        stage.start();

        assertTrue(started.await(5, TimeUnit.SECONDS));
        detector.cancel(view, "report", "view", false);
        cancelled.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        stage.join();

        assertEquals(0, exporter.written().size(), "the export abandoned its work");
        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "a cooperative stage must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: the buggy shape. The caller cancels; the export writes all 50
    // rows regardless, and the stage finishes after the cancellation.
    // -----------------------------------------------------------------------

    @Test
    void exportThatOutlivesTheCancel_isDetected() throws InterruptedException {
        CompletableFuture<String> view = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread stage = new Thread(() -> {
            detector.recordWorkStarted("report", "export", Thread.currentThread());
            started.countDown();
            await(cancelled);
            exporter.exportAll(ROWS);   // nothing here asks whether to stop
            detector.recordWorkCompleted("report", "export", Thread.currentThread());
            finished.countDown();
        }, "export-stage");
        stage.start();

        assertTrue(started.await(5, TimeUnit.SECONDS));
        boolean nowCancelled = detector.cancel(view, "report", "view", false);
        cancelled.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        stage.join();

        assertTrue(nowCancelled, "the view really was cancelled");
        assertTrue(view.isCancelled());
        assertEquals(ROWS, exporter.written().size(),
                "and every row was written anyway - the side effects the caller thought it stopped");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "work after a cancel must be flagged:\n" + report);
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertTrue(report.toString().contains("export completed"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 3: the flag that does nothing. cancel(true) reads as "interrupt
    // the worker"; on a CompletableFuture no interrupt is ever delivered.
    // -----------------------------------------------------------------------

    @Test
    void cancelWithInterruptFlag_isDetectedAsMedium() {
        CompletableFuture<String> view = new CompletableFuture<>();
        detector.cancel(view, "report", "view", true);

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity(),
                "no work outlived the cancel here - the finding is the misleading flag alone");
        assertTrue(report.toString().contains("ignores mayInterruptIfRunning"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 4: no cancellation at all. The export runs to completion and the
    // detector has nothing to say - cancel-related findings need a cancel.
    // -----------------------------------------------------------------------

    @Test
    void exportWithNoCancellation_isClean() {
        detector.recordWorkStarted("report", "export", Thread.currentThread());
        exporter.exportAll(ROWS);
        detector.recordWorkCompleted("report", "export", Thread.currentThread());

        assertEquals(ROWS, exporter.written().size());
        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "nothing was cancelled:\n" + report);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
