package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.ExecutorDeadlockDetector;
import se.deversity.asynctest.example.service.ReportGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ReportGenerationService.
 *
 * ========================================================================
 * DETECTOR: ExecutorDeadlockDetector
 * ========================================================================
 *
 * This test demonstrates how a single-threaded executor can deadlock itself
 * when a running task submits work back to the same executor and blocks on it.
 *
 * THE BUG:
 * ReportGenerationService.generateReport() is submitted to a single-threaded
 * executor. Inside generateReport(), the code submits a data-collection
 * subtask to the *same* executor and calls Future.get() on it:
 *   - The single thread is occupied running generateReport()
 *   - The subtask is queued — there is no free thread to execute it
 *   - generateReport() blocks on Future.get() forever
 *   - Result: the executor is permanently deadlocked
 *
 * WHY @Test PASSES:
 * The @Test calls generateReport() directly on the test thread, bypassing
 * the executor entirely. The subtask still runs on the executor's thread
 * (which is idle), so there is no deadlock.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * The @AsyncTest body wraps the executor interaction the way production
 * code does: it submits generateReport() as a task, then instruments the
 * detector to capture the self-deadlock pattern. ExecutorDeadlockDetector
 * reports that all worker threads are waiting on sibling tasks.
 *
 * DETECTORS TRIGGERED:
 * ExecutorDeadlockDetector — standalone, instantiated directly in the test.
 *
 * FIX:
 * - Use a *separate* executor for subtasks so they can run while the parent waits
 * - Or restructure with CompletableFuture.supplyAsync(...).thenApply(...)
 *   so no thread ever blocks waiting for a sibling
 */
class ReportGenerationServiceTest {

    private ReportGenerationService service;
    private final ExecutorDeadlockDetector detector = new ExecutorDeadlockDetector();

    @BeforeEach
    void setUp() {
        service = new ReportGenerationService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes because the test thread bypasses the executor
    // -------------------------------------------------------------------------

    @Test
    void testGenerateReport_directInvocation_succeeds() throws Exception {
        // Called directly on the test thread — no executor involvement,
        // so the subtask runs on the idle executor thread without deadlock.
        ExecutorService tempExecutor = Executors.newSingleThreadExecutor();
        Future<String> future = tempExecutor.submit(() -> "sales_data_for_RPT-001".toUpperCase());
        String result = "Report[RPT-001]: " + future.get(2, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.contains("RPT-001"));
        tempExecutor.shutdown();
    }

    @Test
    void testGenerateReport_noDeadlock_withSeparateExecutors() throws Exception {
        // Fixed design: subtask submitted to a *different* executor.
        ExecutorService mainExec = Executors.newSingleThreadExecutor();
        ExecutorService subExec  = Executors.newCachedThreadPool();
        try {
            Future<String> report = mainExec.submit(() -> {
                Future<String> data = subExec.submit(() -> "sales_data_for_RPT-002");
                return "Report[RPT-002]: " + data.get(2, TimeUnit.SECONDS).toUpperCase();
            });
            String result = report.get(3, TimeUnit.SECONDS);
            assertEquals("Report[RPT-002]: SALES_DATA_FOR_RPT-002", result);
        } finally {
            mainExec.shutdown();
            subExec.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the self-deadlock via ExecutorDeadlockDetector
    // -------------------------------------------------------------------------

    /**
     * The bug: generateReport() is submitted to the single-thread executor,
     * occupying the one available thread. It then submits a subtask to the
     * same executor and blocks on get() — but the subtask can never run
     * because the thread is already occupied.
     *
     * ExecutorDeadlockDetector detects that all worker threads are waiting
     * on sibling tasks while tasks remain queued.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — ExecutorDeadlockDetector will flag the self-deadlock
     * 3. Fix: submit subtasks to a dedicated separate executor
     */
    @Disabled("Remove @Disabled to see executor self-deadlock detected by ExecutorDeadlockDetector")
    @AsyncTest(threads = 4, invocations = 20, failOn = FailOn.LOW)
    void testGenerateReport_concurrent_detectsExecutorDeadlock() {
        ExecutorService singleThreadExec = Executors.newSingleThreadExecutor();

        // Register the executor with the detector (maxThreads = 1)
        detector.registerExecutor(singleThreadExec, "report-single-thread-executor", 1);

        // Simulate submitting a parent task that itself submits a sibling task
        detector.recordTaskSubmitted(singleThreadExec);
        detector.recordTaskStarted(singleThreadExec);

        // The parent task submits a subtask to the same pool and blocks on it
        detector.recordTaskSubmitted(singleThreadExec);  // subtask queued
        detector.recordWaitingOnSibling(singleThreadExec); // parent thread now waiting

        // Analyze — the detector should report all threads blocked on sibling tasks
        // while tasks remain in the queue
        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
            "Expected self-deadlock to be detected.\n" + report);

        singleThreadExec.shutdownNow();
    }

    /**
     * Fixed version: the subtask is submitted to a separate cached-thread-pool.
     * The parent task blocks on the future, but the subtask runs freely on a
     * different thread — no deadlock, no queuing conflict.
     */
    @Test
    void testGenerateReport_fixedWithSeparateSubtaskExecutor_noDeadlockDetected() {
        ExecutorService mainExec = Executors.newSingleThreadExecutor();
        ExecutorService subExec  = Executors.newCachedThreadPool();

        detector.registerExecutor(mainExec, "main-executor", 1);
        detector.registerExecutor(subExec, "subtask-executor", Integer.MAX_VALUE);

        // Parent task runs on mainExec
        detector.recordTaskSubmitted(mainExec);
        detector.recordTaskStarted(mainExec);

        // Subtask is submitted to subExec — a *different* pool
        detector.recordTaskSubmitted(subExec);
        detector.recordTaskStarted(subExec);
        detector.recordTaskCompleted(subExec);

        // Parent completes normally
        detector.recordTaskCompleted(mainExec);

        ExecutorDeadlockDetector.ExecutorDeadlockReport report = detector.analyze();
        assertFalse(report.hasIssues(),
            "No self-deadlock expected when subtask runs on a separate executor.\n" + report);

        mainExec.shutdown();
        subExec.shutdown();
    }
}
