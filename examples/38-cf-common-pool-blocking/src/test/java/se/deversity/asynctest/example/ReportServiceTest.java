package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

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
 * DETECTORS TRIGGERED:
 *   CompletableFutureCommonPoolBlockingDetector — primary: blocking on common pool
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

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes common-pool blocking
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see common-pool blocking detected by CompletableFutureCommonPoolBlockingDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCFCommonPoolBlocking = true)
    void testGenerateReport_concurrent_detectsPoolBlocking() {
        // Inform the detector that we are about to block on the common pool
        AsyncTestContext.get().cfCommonPoolBlockingMonitor()
                .recordBlockingCall(null, Thread.currentThread(), "fetchData.get");

        String result = service.generateReport("R-" + Thread.currentThread().getId());
        assertNotNull(result, "Report must not be null");
    }
}
