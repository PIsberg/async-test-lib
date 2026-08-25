package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.ExchangerDetector;
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
 * to call exchange() simultaneously. With an odd number of concurrent callers,
 * one thread each round ends up waiting without a partner and hits the 100 ms
 * timeout.
 *
 * THE NUMBER OF THREADS IS PART OF THE BUG:
 * This demonstration runs on 7 threads. An even number of callers all find a
 * partner and nothing times out, so with the 8 it used to use the report was
 * empty, three runs out of three - not because the detector was wrong but
 * because the condition never arose. See issue #346.
 *
 * WHY @Test PASSES:
 * A single-threaded test calls exchangeData() once and gets "[timeout]" because
 * no partner arrives, but the test typically just checks the return value rather
 * than treating a timeout as a failure.
 *
 * WHY @AsyncTest DETECTS:
 * ExchangerDetector.recordTimeout() is called every time a timeout occurs, and
 * hasIssues() gates on any timeout, interruption or null exchange. failOn =
 * FailOn.LOW turns that finding into a failed run.
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

    /**
     * The detector's positive direction: a caller with no partner times out, and that is the
     * finding.
     */
    @Test
    void testExchangerDetector_unmatchedCaller_reports() {
        ExchangerDetector detector = new ExchangerDetector();
        detector.registerExchanger(service.getExchanger(), "data-sync-exchanger");

        detector.recordExchangeStart(service.getExchanger(), "data-sync-exchanger");
        assertEquals("[timeout]", service.exchangeData("lonely"));
        detector.recordTimeout(service.getExchanger());

        assertTrue(detector.analyze().hasIssues(),
                "an exchange with nobody on the other side is the bug this detector exists for");
    }

    /**
     * And the other direction, which is why the thread count matters: two callers find each
     * other, both come back with the other's payload, and there is nothing to report.
     */
    @Test
    void testExchangerDetector_matchedPair_isSilent() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        detector.registerExchanger(service.getExchanger(), "data-sync-exchanger");

        String[] results = new String[2];
        Thread first = new Thread(() -> results[0] = service.exchangeData("from-first"));
        Thread second = new Thread(() -> results[1] = service.exchangeData("from-second"));
        first.start();
        second.start();
        first.join(2000);
        second.join(2000);

        detector.recordExchangeComplete(service.getExchanger(), "data-sync-exchanger", results[0]);
        detector.recordExchangeComplete(service.getExchanger(), "data-sync-exchanger", results[1]);

        assertEquals("from-second", results[0], "each caller gets the other's payload");
        assertEquals("from-first", results[1]);
        assertFalse(detector.analyze().hasIssues(),
                "a matched pair is an Exchanger working, not a finding");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With an odd number of callers arriving together, three pairs form and one thread is left
     * with nobody to trade with. It waits out the 100ms timeout and comes back with the sentinel.
     * ExchangerDetector reports the exchanger that timed out.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      data-sync-exchanger: exchange() timed out waiting for a partner
     * 3. To fix: redesign so callers always arrive in matched pairs, or use a
     *    SynchronousQueue with explicit producer and consumer roles
     */
    @Disabled("Remove @Disabled to see the bug detected by ExchangerDetector")
    // threads is 7, and it is 7 on purpose. An Exchanger pairs its callers, so an even number of
    // them all find a partner and nothing times out: with threads = 8 this demonstration reported
    // nothing, three runs out of three, because the bug it demonstrates could not happen. The odd
    // caller each round is the one left holding the payload. See issue #346.
    @AsyncTest(threads = 7, invocations = 5, detectAll = false,
            detectExchangerIssues = true, failOn = FailOn.LOW)
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
