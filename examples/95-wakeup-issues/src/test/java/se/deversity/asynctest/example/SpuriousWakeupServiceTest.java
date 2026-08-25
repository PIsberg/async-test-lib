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
 * With many threads, some call waitUntilReady() while others call setReady().
 * WakeupDetector records WAIT_ENTER, WAIT_EXIT, and NOTIFY events and identifies
 * threads that exited wait() without a corresponding notify (spurious wakeup)
 * or notifies that fired with no waiters present (lost notification).
 *
 * FIX:
 * Replace "if (!ready) monitor.wait()" with "while (!ready) { monitor.wait(); }"
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
     * Mixed threads wait and signal concurrently. WakeupDetector records
     * spurious wakeups (exit without notify) and lost notifications (notify
     * with no waiters) triggered by the if-instead-of-while bug.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: change if(!ready) to while(!ready) in waitUntilReady()
     */
    @Disabled("Remove @Disabled to see the bug detected by WakeupDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectWakeupIssues = true, failOn = FailOn.LOW)
    void test_concurrent_detectsSpuriousWakeup() {
        Object monitor = service.getMonitor();
        String name = Thread.currentThread().getName();

        if (name.hashCode() % 3 == 0) {
            // Producer thread: call setReady() and record the notify
            service.setReady();
            AsyncTestContext.wakeupDetector().recordNotify(monitor, true);
        } else {
            // Consumer thread: record wait-enter, wait, record wait-exit
            AsyncTestContext.wakeupDetector().recordWaitEnter(monitor);
            try {
                service.waitUntilReady();
                // wasNotified = service.isReady() would be true only if genuinely notified
                boolean genuineWakeup = service.isReady();
                AsyncTestContext.wakeupDetector().recordWaitExit(monitor, genuineWakeup);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AsyncTestContext.wakeupDetector().recordWaitExit(monitor, false);
            } finally {
                service.reset();
            }
        }
    }
}
