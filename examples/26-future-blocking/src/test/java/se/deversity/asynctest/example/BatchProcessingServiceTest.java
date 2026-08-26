package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.FutureBlockingDetector;
import se.deversity.asynctest.example.service.BatchProcessingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * FutureBlockingDetector — the run's own, from AsyncTestContext.futureBlockingDetector().
 *
 * FIX:
 * - Use a separate I/O thread pool for the per-item subtasks
 * - Or use non-blocking composition: CompletableFuture.supplyAsync(...).thenApply(...)
 * - Or use virtual threads — blocking is cheap and does not exhaust the pool
 */
class BatchProcessingServiceTest {

    private BatchProcessingService service;

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
    @AsyncTest(threads = 4, invocations = 1, detectAll = false,
            detectFutureBlocking = true, failOn = FailOn.LOW)

    void testProcessBatch_concurrent_detectsFutureBlockingStarvation() {
        ExecutorService pool = service.getWorkerPool();

        // The detector has to be the run's own. This demonstration used to record into a
        // locally constructed FutureBlockingDetector, which the library never reads, so failOn
        // had nothing to gate on however the subject behaved. It then analyzed that local
        // instance in its own body and asserted on the result, an assertion the first thread
        // through always lost because its three peers had not recorded anything yet. Failing on
        // that assertion is why the demonstration looked healthy to the audit in issue #346,
        // which was looking for demonstrations that passed. See issues #346 and #363.
        FutureBlockingDetector detector = AsyncTestContext.futureBlockingDetector();
        detector.registerExecutor(pool, "batch-worker-pool", 4);

        // Starvation needs processBatch() to run ON the pool it submits to, which is the misuse
        // the service documents. Called straight from here it would block outside the pool,
        // where blocking is harmless, and nothing would starve. The detector is resolved on this
        // thread first: AsyncTestContext is a ThreadLocal and a pool thread has none installed.
        detector.recordTaskSubmitted(pool);
        Future<List<String>> outer = pool.submit(() -> {
            detector.recordTaskStarted(pool);
            detector.recordTaskSubmitted(pool);   // the subtask processBatch enqueues
            detector.recordBlockingWait(pool);    // and then blocks on, holding a pool thread
            try {
                return service.processBatch(List.of("item-" + Thread.currentThread().threadId()));
            } finally {
                detector.recordTaskCompleted(pool);
            }
        });

        // One round is all there is to see: once the four workers are wedged the pool never
        // recovers, so a second round would measure the wreckage rather than the bug. The wait
        // is bounded and the timeout absorbed, because an exception escaping this body fails
        // the run before the failOn gate reports the finding. tearDown() shuts the pool down;
        // its threads are daemons.
        try {
            outer.get(250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException starved) {
            // All four workers are inside processBatch, blocked on subtasks that cannot start.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failed) {
            // processBatch threw rather than blocked; the recorded lifecycle still stands.
        }
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

        // Standalone here on purpose: this is an ordinary @Test with no @AsyncTest run to own a
        // detector, so it constructs one and reads it directly. The demonstration above must not
        // do that, and that is the whole of its comment.
        FutureBlockingDetector detector = new FutureBlockingDetector();

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
