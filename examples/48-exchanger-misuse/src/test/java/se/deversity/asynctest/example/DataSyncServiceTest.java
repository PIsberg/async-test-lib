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
 * DataSyncService.exchangeData() calls Exchanger.exchange() with no timeout, and
 * an Exchanger needs exactly two threads to meet. With an odd number of concurrent
 * callers, one thread each round has nobody to pair with and never returns.
 *
 * THE NUMBER OF THREADS IS PART OF THE BUG:
 * This demonstration runs on 7 threads. An even number of callers all find a
 * partner and nothing is left waiting, so with the 8 it used to use the report was
 * empty, three runs out of three - not because the detector was wrong but
 * because the condition never arose. See issue #346.
 *
 * WHY @Test PASSES:
 * A single-threaded test either calls the bounded exchangeDataWithin() and gets
 * "[timeout]", or never calls exchangeData() at all, because a lone caller hangs.
 *
 * WHY @AsyncTest DETECTS:
 * Each body records the start of its exchange and, when exchange() returns, its
 * completion. The odd caller never returns, so the round runs into timeoutMs, the
 * runner interrupts the workers, and the InterruptedException leaves that start
 * with nothing that ended it. ExchangerDetector reports an exchange that was
 * started and never ended, and the timeout message names the detector.
 *
 * WHAT IS NOT THE BUG:
 * A timed exchange that times out and handles it has left the exchanger, and
 * ExchangerDetector does not report it (#585). That is the fix, not the misuse.
 *
 * FIX:
 * Use exchangeDataWithin() so a caller left without a partner gives up, or ensure
 * callers always arrive in pairs.
 */
class DataSyncServiceTest {

    private static final String NAME = "data-sync-exchanger";

    private DataSyncService service;

    @BeforeEach
    void setUp() {
        service = new DataSyncService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testExchangeDataWithin_singleCaller_returnsTimeout() {
        // Single caller with no partner: the bounded exchange times out and returns the sentinel
        assertEquals("[timeout]", service.exchangeDataWithin("hello", 100),
                "Without a partner thread, exchangeDataWithin should time out");
    }

    @Test
    void testGetExchanger_notNull() {
        assertNotNull(service.getExchanger(), "Exchanger must not be null");
    }

    /**
     * The detector's positive direction: a caller of the unbounded exchange with no partner is
     * still parked inside exchange() when the detector analyses, and that is the finding.
     */
    @Test
    void testExchangerDetector_callerLeftWithoutPartner_reports() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        detector.registerExchanger(service.getExchanger(), NAME);

        Thread lonely = new Thread(() -> {
            detector.recordExchangeStart(service.getExchanger(), NAME);
            try {
                String received = service.exchangeData("lonely");
                detector.recordExchangeComplete(service.getExchanger(), NAME, received);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // this test's cleanup, after the analysis
            }
        });
        lonely.setDaemon(true);
        lonely.start();
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (lonely.getState() != Thread.State.WAITING) {
                assertTrue(System.nanoTime() < deadline, "the caller never parked in exchange()");
                Thread.sleep(5);
            }
            assertTrue(detector.analyze().hasIssues(),
                    "an exchange with nobody on the other side is the bug this detector exists for");
        } finally {
            lonely.interrupt();
            lonely.join(2000);
        }
    }

    /**
     * The fix stays silent: the same lone caller on the bounded exchange times out, handles it,
     * and has left the exchanger.
     */
    @Test
    void testExchangerDetector_handledTimeout_isSilent() {
        ExchangerDetector detector = new ExchangerDetector();
        detector.registerExchanger(service.getExchanger(), NAME);

        detector.recordExchangeStart(service.getExchanger(), NAME);
        assertEquals("[timeout]", service.exchangeDataWithin("lonely", 50));
        detector.recordTimeout(service.getExchanger());

        assertFalse(detector.analyze().hasIssues(),
                "a caller that gave up after its timeout is not waiting for anyone");
    }

    /**
     * And a matched pair, which is why the thread count matters: two callers find each other,
     * both come back with the other's payload, and there is nothing to report.
     */
    @Test
    void testExchangerDetector_matchedPair_isSilent() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        detector.registerExchanger(service.getExchanger(), NAME);

        String[] results = new String[2];
        Thread first = new Thread(() -> {
            detector.recordExchangeStart(service.getExchanger(), NAME);
            results[0] = service.exchangeDataWithin("from-first", 2000);
            detector.recordExchangeComplete(service.getExchanger(), NAME, results[0]);
        });
        Thread second = new Thread(() -> {
            detector.recordExchangeStart(service.getExchanger(), NAME);
            results[1] = service.exchangeDataWithin("from-second", 2000);
            detector.recordExchangeComplete(service.getExchanger(), NAME, results[1]);
        });
        first.start();
        second.start();
        first.join(3000);
        second.join(3000);

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
     * with nobody to trade with. It stays inside exchange() until the round's timeout, and
     * ExchangerDetector reports the exchange that started and never ended.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test. The round times out after 2 s, the report says
     *      data-sync-exchanger: 1 of 7 started exchange(s) never ended
     *    and the timeout message names ExchangerDetector
     * 3. To fix: call exchangeDataWithin() so the odd caller gives up, or make callers
     *    arrive in matched pairs
     */
    @Disabled("Remove @Disabled to see the bug detected by ExchangerDetector")
    // threads is 7, and it is 7 on purpose. An Exchanger pairs its callers, so an even number of
    // them all find a partner and nobody is left waiting: with threads = 8 this demonstration
    // reported nothing, three runs out of three, because the bug it demonstrates could not happen.
    // The odd caller each round is the one left holding the payload. See issue #346.
    @AsyncTest(threads = 7, invocations = 5, timeoutMs = 2000, detectAll = false,
            detectExchangerIssues = true, failOn = FailOn.LOW)
    void testExchangeData_concurrent_detectsOrphanedCaller() throws InterruptedException {
        ExchangerDetector detector = AsyncTestContext.exchangerDetector();
        detector.registerExchanger(service.getExchanger(), NAME);

        // Signal that this thread is about to attempt an exchange
        detector.recordExchangeStart(service.getExchanger(), NAME);

        // The odd caller never gets past this line; the runner's timeout interrupts it, and the
        // InterruptedException leaves its start without a completion
        String result = service.exchangeData("data-" + Thread.currentThread().threadId());

        detector.recordExchangeComplete(service.getExchanger(), NAME, result);
    }
}
