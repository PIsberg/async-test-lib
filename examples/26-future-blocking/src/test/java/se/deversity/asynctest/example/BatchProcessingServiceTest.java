package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.FutureBlockingDetector;
import se.deversity.asynctest.example.service.BatchProcessingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for BatchProcessingService.
 *
 * ========================================================================
 * DETECTOR: FutureBlockingDetector
 * ========================================================================
 *
 * This test demonstrates how blocking Future.get() calls inside worker threads
 * can starve a bounded thread pool of all available capacity.
 *
 * THE BUG:
 * BatchProcessingService.processBatch() is designed to be called from a
 * thread in the worker pool. It submits N subtasks to the same pool and then
 * blocks on each Future.get():
 *   - With 4 pool threads and 4 concurrent callers, all 4 threads are blocked
 *     waiting on futures
 *   - The futures' tasks are queued — there are no free threads to run them
 *   - The pool is fully starved: zero throughput, indefinite hang
 *
 * WHY @Test PASSES:
 * The test thread calls processBatch() directly. The pool's 4 threads are idle,
 * so the subtasks start immediately and the Future.get() calls return quickly.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * With multiple concurrent test threads each invoking the pattern, the
 * FutureBlockingDetector observes that all registered pool threads are in a
 * blocking wait state while tasks remain queued — reporting starvation risk.
 *
 * DETECTORS TRIGGERED:
 * FutureBlockingDetector — standalone, instantiated directly in the test.
 *
 * FIX:
 * - Use a separate I/O thread pool for the per-item subtasks
 * - Or use non-blocking composition: CompletableFuture.supplyAsync(...).thenApply(...)
 * - Or use virtual threads — blocking is cheap and does not exhaust the pool
 */
class BatchProcessingServiceTest {

    private BatchProcessingService service;
    private final FutureBlockingDetector detector = new FutureBlockingDetector();

    @BeforeEach
    void setUp() {
        service = new BatchProcessingService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes because the test thread is not a pool worker
    // -------------------------------------------------------------------------

    @Test
    void testProcessBatch_singleCaller_allItemsProcessed() throws Exception {
        List<String> results = service.processBatch(
                List.of("alpha", "beta", "gamma", "delta"));

        assertEquals(4, results.size());
        assertTrue(results.contains("ALPHA_PROCESSED"));
        assertTrue(results.contains("BETA_PROCESSED"));
        assertTrue(results.contains("GAMMA_PROCESSED"));
        assertTrue(results.contains("DELTA_PROCESSED"));
    }

    @Test
    void testProcessBatch_emptyList_returnsEmpty() throws Exception {
        List<String> results = service.processBatch(List.of());
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testProcessBatch_singleItem_succeeds() throws Exception {
        List<String> results = service.processBatch(List.of(" invoice-001 "));
        assertEquals(1, results.size());
        assertEquals("INVOICE-001_PROCESSED", results.get(0));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes pool starvation via FutureBlockingDetector
    // -------------------------------------------------------------------------

    /**
     * The bug: with multiple concurrent callers each submitting subtasks to the
     * same 4-thread pool and blocking on the results, all 4 threads are
     * occupied in blocking waits. Queued subtasks can never execute.
     *
     * FutureBlockingDetector reports that all workers are blocked while tasks
     * remain queued — a starvation risk.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — FutureBlockingDetector will flag the starvation
     * 3. Fix: submit subtasks to a dedicated separate executor
     */
    @Disabled("Remove @Disabled to see future-blocking starvation detected by FutureBlockingDetector")
    @AsyncTest(threads = 4, invocations = 20, failOn = FailOn.LOW)
    void testProcessBatch_concurrent_detectsFutureBlockingStarvation() {
        ExecutorService pool = Executors.newFixedThreadPool(4);

        // Register the pool (4 threads max)
        detector.registerExecutor(pool, "batch-worker-pool", 4);

        // Simulate each of the 4 worker threads submitting a subtask and then blocking
        detector.recordTaskSubmitted(pool);
        detector.recordTaskStarted(pool);

        // Each worker blocks waiting for a subtask
        detector.recordTaskSubmitted(pool); // subtask enqueued
        detector.recordBlockingWait(pool);  // this thread is now blocking on it

        // With 4 threads all in blocking waits and 4 subtasks queued, the pool is starved.
        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertTrue(report.hasIssues(),
            "Expected future-blocking starvation to be detected.\n" + report);

        pool.shutdownNow();
    }

    /**
     * Fixed version: a dedicated cached thread pool handles the per-item subtasks.
     * The batch-orchestrating threads block on futures, but the subtasks run on
     * a separate pool and complete without delay — no starvation.
     */
    @Test
    void testProcessBatch_fixedWithSeparateSubtaskPool_noStarvationDetected()
            throws Exception {
        ExecutorService orchestratorPool = Executors.newFixedThreadPool(4);
        ExecutorService subtaskPool = Executors.newCachedThreadPool();

        detector.registerExecutor(orchestratorPool, "orchestrator-pool", 4);
        detector.registerExecutor(subtaskPool, "subtask-pool", Integer.MAX_VALUE);

        // Orchestrator thread submits subtask to the *separate* pool and blocks
        detector.recordTaskSubmitted(orchestratorPool);
        detector.recordTaskStarted(orchestratorPool);

        detector.recordTaskSubmitted(subtaskPool);
        detector.recordTaskStarted(subtaskPool);
        detector.recordTaskCompleted(subtaskPool);

        // Orchestrator unblocks and completes
        detector.recordTaskCompleted(orchestratorPool);

        FutureBlockingDetector.FutureBlockingReport report = detector.analyze();
        assertFalse(report.hasIssues(),
            "No starvation expected when subtasks run on a separate pool.\n" + report);

        // Verify the actual fixed implementation works correctly
        Future<List<String>> batchFuture = orchestratorPool.submit(() -> {
            List<String> results = new java.util.ArrayList<>();
            List<Future<String>> subtasks = List.of("a", "b", "c").stream()
                    .map(item -> subtaskPool.submit(() -> item.toUpperCase() + "_DONE"))
                    .toList();
            for (Future<String> f : subtasks) {
                results.add(f.get(2, TimeUnit.SECONDS));
            }
            return results;
        });

        List<String> results = batchFuture.get(5, TimeUnit.SECONDS);
        assertEquals(3, results.size());

        orchestratorPool.shutdown();
        subtaskPool.shutdown();
    }
}
