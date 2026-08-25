package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.CompletableFutureCommonPoolBlockingDetector;
import se.deversity.asynctest.example.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ReportService.
 *
 * ========================================================================
 * DETECTOR: CompletableFutureCommonPoolBlockingDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * ReportService.generateReport() submits work to ForkJoinPool.commonPool()
 * (the default when no executor is supplied to supplyAsync). Inside the outer
 * task it calls fetchData() which also submits to the common pool and then
 * blocks on .get(). A common-pool thread blocks waiting for another
 * common-pool thread — under concurrent load this exhausts pool parallelism.
 *
 * WHY @Test PASSES:
 * The common pool has more threads than the single call requires. The inner
 * task is picked up by a free thread, .get() returns quickly, and the outer
 * task completes. No starvation occurs with just one call.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads all call generateReport() concurrently. Every outer task submits
 * an inner task and blocks. The common pool (parallelism ≈ CPU count − 1)
 * fills up with blocked outer threads, and inner tasks cannot be scheduled.
 * CompletableFutureCommonPoolBlockingDetector records the blocking .get() call
 * made on a common-pool thread and reports the pool starvation pattern.
 *
 * It is deliberately narrow: recordBlockingCall ignores any future it was not first
 * told about through recordCommonPoolSubmission, because waiting on a future from an
 * executor you own starves nobody and is ordinary code. Both calls therefore have to
 * happen, in that order, from inside the code doing the work; ReportService.observeCommonPool
 * is the seam that arranges it.
 *
 * DETECTOR ENABLED HERE:
 *   CompletableFutureCommonPoolBlockingDetector — a wait on a common-pool future, from
 *   inside the common pool. It is the only one this demonstration switches on, so it is
 *   the only one that can report.
 *
 * FIX: supply a dedicated ExecutorService to all supplyAsync() calls.
 */
class ReportServiceTest {

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testGenerateReport_singleCall_works() {
        String result = service.generateReport("R-001");
        assertNotNull(result);
        assertTrue(result.contains("R-001"));
    }

    @Test
    void testGenerateReport_twoSequentialCalls_work() {
        String r1 = service.generateReport("R-001");
        String r2 = service.generateReport("R-002");
        assertNotNull(r1);
        assertNotNull(r2);
    }

    /**
     * The detector's positive direction, driven by the real service: a future submitted to the
     * common pool and then waited on from inside that pool.
     */
    @Test
    void testGenerateReport_commonPoolWait_reports() {
        var detector = new CompletableFutureCommonPoolBlockingDetector();
        wire(detector);

        service.generateReport("R-100");

        assertTrue(detector.analyze().hasIssues(),
                "a get() on a common-pool future, from a common-pool thread, is the bug");
    }

    /**
     * And the other direction, which is the whole point of the detector's narrowness: a wait on
     * a future that was <em>not</em> submitted to the common pool is not a finding. Blocking a
     * thread you own is ordinary code.
     */
    @Test
    void testWait_onFutureFromADedicatedExecutor_isSilent() {
        var detector = new CompletableFutureCommonPoolBlockingDetector();
        ExecutorService dedicated = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<String> future =
                    CompletableFuture.supplyAsync(() -> "data", dedicated);
            // Never registered as a common-pool submission, because it is not one.
            detector.recordBlockingCall(future, Thread.currentThread(), "Future.get");
            future.join();
        } finally {
            dedicated.shutdown();
        }

        assertFalse(detector.analyze().hasIssues(),
                "waiting on a future from your own executor starves nobody");
    }

    private void wire(CompletableFutureCommonPoolBlockingDetector detector) {
        service.observeCommonPool(
                (future, name) ->
                        detector.recordCommonPoolSubmission(future, Thread.currentThread(), name),
                (future, callType) ->
                        detector.recordBlockingCall(future, Thread.currentThread(), callType));
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes common-pool blocking
    // -----------------------------------------------------------------------

    /**
     * The bug: eight threads each ask for a report. Every report runs on the common pool and,
     * from inside it, waits on another common-pool task. The pool has one worker per core and
     * every parallel stream in the JVM shares it.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with one line per common-pool worker, each carrying the
     *    number of times that worker did it:
     *      Thread 'ForkJoinPool.commonPool-worker-1' made blocking call (Future.get) inside
     *      CompletableFuture 'fetchData' running on the common ForkJoinPool (x53)
     * 3. Fix: pass a dedicated Executor to every supplyAsync() that may block
     */
    @Disabled("Remove @Disabled to see common-pool blocking detected by CompletableFutureCommonPoolBlockingDetector")
    // invocations was held at 5 while the detector emitted one line per blocking call: 400 body
    // executions produced 400 near-identical lines. The report now collapses identical findings
    // and counts them, so this is back at 50. See issue #351.
    @AsyncTest(threads = 8, invocations = 50, detectAll = false,
            detectCFCommonPoolBlocking = true, failOn = FailOn.LOW)
    void testGenerateReport_concurrent_detectsPoolBlocking() {
        // This demonstration used to call recordBlockingCall(null, ...) from the test body.
        // The detector returns immediately on a null future, and ignores any future it was not
        // first told about through recordCommonPoolSubmission, so the call was a no-op twice
        // over and the run reported nothing. See issue #346.
        var detector = AsyncTestContext.cfCommonPoolBlockingMonitor();
        service.observeCommonPool(
                (future, name) ->
                        detector.recordCommonPoolSubmission(future, Thread.currentThread(), name),
                (future, callType) ->
                        detector.recordBlockingCall(future, Thread.currentThread(), callType));

        assertNotNull(service.generateReport("R-" + Thread.currentThread().threadId()),
                "Report must not be null");
    }
}
