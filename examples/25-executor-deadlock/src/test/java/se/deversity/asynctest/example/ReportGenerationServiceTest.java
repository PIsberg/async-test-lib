package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.ExecutorDeadlockDetector;
import se.deversity.asynctest.example.service.ReportGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
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
 * when a running task submits work back to the same executor and waits on it.
 *
 * THE BUG:
 * ReportGenerationService.generateReport() is submitted to a single-threaded
 * executor. Inside generateReport(), the code submits a data-collection
 * subtask to the *same* executor and waits on it:
 *   - The single thread is occupied running generateReport()
 *   - The subtask is queued — there is no free thread to execute it
 *   - generateReport() waits for a result that cannot arrive
 *   - Result: the report never completes
 *
 * WHY @Test PASSES:
 * The @Test calls generateReport() directly on the test thread, bypassing
 * the executor entirely. The subtask runs on the executor's thread, which is
 * idle, so there is no deadlock. Submitting the parent is the step that turns
 * a working method into a deadlocked one, and a sequential test never takes it.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * The demonstration submits the report the way production does, through
 * submitReport(), so the parent really does occupy the only thread.
 * ReportGenerationService.observeExecutor hands ExecutorDeadlockDetector the
 * four facts it needs: submitted, started, completed, and, the one nothing
 * else can supply, that a running task started waiting on a sibling. The
 * detector reports when every worker is waiting on a sibling while tasks are
 * still queued.
 *
 * DETECTOR ENABLED HERE:
 * ExecutorDeadlockDetector — a task waiting on a sibling that shares its only
 * thread. It is the only one this demonstration switches on, so it is the only
 * one that can report.
 *
 * FIX:
 * - Use a *separate* executor for subtasks so they can run while the parent waits
 * - Or restructure with CompletableFuture.supplyAsync(...).thenApply(...)
 *   so no thread ever waits for a sibling
 */
class ReportGenerationServiceTest {

    private ReportGenerationService service;

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

    /**
     * Called from the caller's own thread, generateReport() works. The executor thread is
     * idle, so the subtask it submits runs immediately. This is the false confidence.
     */
    @Test
    void testGenerateReport_directInvocation_succeeds() throws Exception {
        String report = service.generateReport("RPT-001");

        assertEquals("Report[RPT-001]: SALES_DATA_FOR_RPT-001", report);
    }

    /**
     * And this is the same call, submitted the way production submits it. Same method, same
     * data, and it cannot complete: the parent holds the only thread the subtask could use.
     */
    @Test
    void testSubmitReport_ownExecutor_selfDeadlocks() {
        Future<String> report = service.submitReport("RPT-002");

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> report.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("self-deadlock"),
                "expected the self-deadlock message, got: " + failure.getCause().getMessage());
    }

    @Test
    void testGenerateReport_noDeadlock_withSeparateExecutors() throws Exception {
        // Fixed design: subtask submitted to a *different* executor.
        ExecutorService mainExec = Executors.newSingleThreadExecutor();
        ExecutorService subExec  = Executors.newCachedThreadPool();
        try {
            Future<String> report = mainExec.submit(() -> {
                Future<String> data = subExec.submit(() -> "sales_data_for_RPT-003");
                return "Report[RPT-003]: " + data.get(2, TimeUnit.SECONDS).toUpperCase();
            });
            String result = report.get(3, TimeUnit.SECONDS);
            assertEquals("Report[RPT-003]: SALES_DATA_FOR_RPT-003", result);
        } finally {
            mainExec.shutdown();
            subExec.shutdown();
        }
    }

    /**
     * The detector's positive direction, spelled out: one worker, that worker waiting on a
     * sibling, and a task still queued behind it.
     */
    @Test
    void testExecutorDeadlockDetector_waitingOnSiblingWithQueuedWork_reports() {
        ExecutorDeadlockDetector detector = new ExecutorDeadlockDetector();
        Object executor = new Object();
        detector.registerExecutor(executor, "single-thread", 1);

        detector.recordTaskSubmitted(executor);
        detector.recordTaskStarted(executor);
        detector.recordTaskSubmitted(executor);       // the sibling, now queued
        detector.recordWaitingOnSibling(executor);

        assertTrue(detector.analyze().hasIssues(),
                "one worker waiting on a sibling with work still queued is the self-deadlock");
    }

    /**
     * And the negative direction: the same parent and subtask on two different pools is the
     * recommended fix, so it has to stay silent.
     */
    @Test
    void testExecutorDeadlockDetector_siblingOnAnotherPool_isSilent() {
        ExecutorDeadlockDetector detector = new ExecutorDeadlockDetector();
        Object mainPool = new Object();
        Object subPool = new Object();
        detector.registerExecutor(mainPool, "main", 1);
        detector.registerExecutor(subPool, "subtasks", Integer.MAX_VALUE);

        detector.recordTaskSubmitted(mainPool);
        detector.recordTaskStarted(mainPool);
        detector.recordTaskSubmitted(subPool);
        detector.recordTaskStarted(subPool);
        detector.recordTaskCompleted(subPool);
        detector.recordTaskCompleted(mainPool);

        assertFalse(detector.analyze().hasIssues(),
                "a subtask on a separate pool is the fix, not a finding");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the self-deadlock via ExecutorDeadlockDetector
    // -------------------------------------------------------------------------

    /**
     * The bug: generateReport() is submitted to the single-thread executor,
     * occupying the one available thread. It then submits a subtask to the
     * same executor and waits on it, and the subtask can never run because
     * the thread is already taken.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      report-executor: all 1 worker(s) are waiting on sibling tasks while N task(s)
     *      remain queued
     * 3. Fix: submit subtasks to a dedicated separate executor
     */
    @Disabled("Remove @Disabled to see executor self-deadlock detected by ExecutorDeadlockDetector")
    @AsyncTest(threads = 4, invocations = 2, detectAll = false,
            detectExecutorDeadlock = true, failOn = FailOn.LOW)
    void testGenerateReport_concurrent_detectsExecutorDeadlock() {
        // This demonstration used to hand-record a lifecycle into a locally constructed
        // detector and assert on it, without ever calling the service. It proved that the
        // detector's arithmetic works, which was never in doubt, and told the library nothing:
        // failOn had no finding to gate on. See issue #346.
        ExecutorDeadlockDetector detector = AsyncTestContext.executorDeadlockDetector();
        ExecutorService executor = service.getExecutor();
        detector.registerExecutor(executor, "report-executor", 1);
        service.observeExecutor(
                () -> detector.recordTaskSubmitted(executor),
                () -> detector.recordTaskStarted(executor),
                () -> detector.recordTaskCompleted(executor),
                () -> detector.recordWaitingOnSibling(executor));

        Future<String> report = service.submitReport("RPT-" + Thread.currentThread().threadId());

        // The report never assembles: the subtask it waits for is queued behind it.
        assertThrows(ExecutionException.class, () -> report.get(5, TimeUnit.SECONDS));
    }
}
