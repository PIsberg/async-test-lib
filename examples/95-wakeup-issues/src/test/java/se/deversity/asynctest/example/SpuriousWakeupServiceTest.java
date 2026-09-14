package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.SpuriousWakeupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SpuriousWakeupService.
 *
 * ========================================================================
 * DETECTOR: WakeupDetector
 * ========================================================================
 *
 * THE BUG:
 * waitUntilReady() uses "if (!ready) monitor.wait()" instead of a while loop.
 * The JVM permits wait() to return without a notify() (spurious wakeup). When
 * this happens, the thread proceeds with ready == false, causing the downstream
 * logic to run in an uninitialized state.
 *
 * WHY @Test PASSES:
 * Single-threaded tests call setReady() before waitUntilReady() or sequentially
 * after, so the notify is either already set or arrives in the right order.
 * Spurious wakeups never manifest in isolation.
 *
 * WHY @AsyncTest DETECTS:
 * Eight consumers wait at once with nobody signalling. WakeupDetector records
 * WAIT_ENTER, WAIT_EXIT and NOTIFY events and reports a wait that returned with
 * no notify accounting for it, after which the thread went on without waiting
 * again. A while loop would record a second wait there; the if guard does not.
 * A notify that finds no waiter is not reported: setting a flag and then
 * notifying is the correct handshake.
 *
 * FIX:
 * Replace "if (!ready) monitor.wait(...)" with "while (!ready) { monitor.wait(...); }"
 */
class SpuriousWakeupServiceTest {

    private SpuriousWakeupService service;

    @BeforeEach
    void setUp() {
        service = new SpuriousWakeupService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testSetReady_flagBecomesTrue() {
        service.setReady();
        assertTrue(service.isReady());
    }

    @Test
    void testWaitUntilReady_alreadyReady_returnsImmediately() throws Exception {
        service.setReady();
        assertDoesNotThrow(() -> service.waitUntilReady());
    }

    @Test
    void testGetMonitor_nonNull() {
        assertNotNull(service.getMonitor());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the spurious wakeup vulnerability
    // -------------------------------------------------------------------------

    /**
     * Consumers call waitUntilReady() while nobody calls setReady(), so every return from
     * wait() is one no notify accounted for, which is exactly what a spurious wakeup looks like
     * to the if guard. WakeupDetector reports a wait that returned with no notify after which
     * the thread went on without waiting again: the if-instead-of-while bug.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: change if(!ready) to while(!ready) in waitUntilReady(); the loop then waits
     *    again after every unsignalled return, and a producer's setReady() ends it
     */
    @Disabled("Remove @Disabled: each consumer's wait() returns with nobody having notified and the if guard "
            + "lets it proceed with ready still false; the failure names WakeupDetector's finding")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectWakeupIssues = true, failOn = FailOn.LOW)
    void test_concurrent_detectsSpuriousWakeup() {
        Object monitor = service.getMonitor();
        var detector = AsyncTestContext.wakeupDetector();
        detector.recordWaitEnter(monitor);
        try {
            service.waitUntilReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // True only if the consumer really saw the flag set; nobody sets it here.
        detector.recordWaitExit(monitor, service.isReady());
    }
}
