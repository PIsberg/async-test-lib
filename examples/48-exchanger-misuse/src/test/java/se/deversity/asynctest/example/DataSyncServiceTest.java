package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.DataSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for DataSyncService.
 *
 * ========================================================================
 * DETECTOR: ExchangerDetector
 * ========================================================================
 *
 * THE BUG:
 * DataSyncService uses an Exchanger<String> that requires exactly two threads
 * to call exchange() simultaneously. With an odd number of concurrent callers
 * (e.g., 8 threads × 50 invocations = 400 calls) one thread always ends up
 * waiting without a partner, hitting the 100 ms timeout repeatedly.
 *
 * WHY @Test PASSES:
 * A single-threaded test calls exchangeData() once and gets "[timeout]" because
 * no partner arrives, but the test typically just checks the return value rather
 * than treating a timeout as a failure.
 *
 * WHY @AsyncTest DETECTS:
 * ExchangerDetector.recordTimeout() is called every time a timeout occurs.
 * At analysis time the detector reports repeated timeouts on the same Exchanger
 * instance as a structural imbalance in usage.
 *
 * FIX:
 * Ensure callers always arrive in pairs, or replace the Exchanger with a
 * SynchronousQueue that supports an explicit producer/consumer split.
 */
class DataSyncServiceTest {

    private DataSyncService service;

    @BeforeEach
    void setUp() {
        service = new DataSyncService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testExchangeData_singleCaller_returnsTimeout() {
        // Single caller with no partner: times out and returns sentinel value
        String result = service.exchangeData("hello");
        assertEquals("[timeout]", result,
                "Without a partner thread, exchangeData should timeout");
    }

    @Test
    void testGetExchanger_notNull() {
        assertNotNull(service.getExchanger(), "Exchanger must not be null");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 threads all calling exchangeData(), the total call count is not
     * guaranteed to be divisible into perfect pairs each round. ExchangerDetector
     * records each timeout and reports the structural imbalance.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: redesign so callers always arrive in matched pairs
     */
    @Disabled("Remove @Disabled to see the bug detected by ExchangerDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectExchangerIssues = true)
    void testExchangeData_concurrent_detectsTimeout() {
        // Register the exchanger so the detector can track it by identity
        AsyncTestContext.exchangerMonitor()
                .registerExchanger(service.getExchanger(), "data-sync-exchanger");

        // Signal that this thread is about to attempt an exchange
        AsyncTestContext.exchangerMonitor()
                .recordExchangeStart(service.getExchanger(), "data-sync-exchanger");

        String payload = "data-" + Thread.currentThread().threadId();
        String result = service.exchangeData(payload);

        if ("[timeout]".equals(result)) {
            // No partner arrived — record the timeout for the detector
            AsyncTestContext.exchangerMonitor()
                    .recordTimeout(service.getExchanger());
        } else {
            AsyncTestContext.exchangerMonitor()
                    .recordExchangeComplete(service.getExchanger(), "data-sync-exchanger", result);
        }
    }
}
