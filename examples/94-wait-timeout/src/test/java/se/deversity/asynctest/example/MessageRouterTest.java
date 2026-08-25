package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.MessageRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for MessageRouter.
 *
 * ========================================================================
 * DETECTOR: WaitTimeoutDetector
 * ========================================================================
 *
 * THE BUG:
 * MessageRouter.waitForMessage() calls lock.wait() with no timeout argument. Under
 * concurrent load, if deliver() fires before waitForMessage() is called (or is
 * never called at all), the waiting thread blocks forever. This causes the test
 * suite — and the application — to hang silently.
 *
 * WHY @Test PASSES:
 * Single-threaded tests call deliver() and waitForMessage() sequentially, so the
 * notification is always in place before wait() is reached. The timeout issue
 * is never observable.
 *
 * WHY @AsyncTest DETECTS:
 * Multiple threads all call waitForMessage() concurrently. WaitTimeoutDetector
 * sees recordInfiniteWait() calls on the same monitor and flags them: without a
 * timeout there is no way to bound the wait if notify() is delayed or lost.
 *
 * FIX:
 * Replace lock.wait() with lock.wait(5000) inside a while (!messageAvailable) loop,
 * then throw IllegalStateException if the loop exits due to a timeout.
 */
class MessageRouterTest {

    private MessageRouter router;

    @BeforeEach
    void setUp() {
        router = new MessageRouter();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testDeliver_thenWait_returnsMessage() throws Exception {
        router.deliver("hello");
        String msg = router.waitForMessage();
        assertEquals("hello", msg);
    }

    @Test
    void testDeliver_setsMessage() {
        // Verify the router accepts a message without throwing
        assertDoesNotThrow(() -> router.deliver("test-message"));
    }

    @Test
    void testGetLock_returnsNonNull() {
        assertNotNull(router.getLock(), "Internal lock must not be null");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * Multiple threads all call waitForMessage() with no timeout. The detector
     * records each infinite wait() and reports the risk.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: replace lock.wait() with lock.wait(5000) inside a while loop
     */
    @Disabled("Remove @Disabled to see the bug detected by WaitTimeoutDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectWaitTimeout = true, failOn = FailOn.LOW)
    void test_concurrent_detectsInfiniteWait() {
        Object lock = router.getLock();
        String threadName = Thread.currentThread().getName();

        // Instrument: record this thread is about to call wait() with no timeout
        AsyncTestContext.waitTimeoutMonitor()
                .recordInfiniteWait(lock, "message-router-lock", threadName);

        // Simulate the concurrent scenario: half threads wait, half deliver
        if (threadName.hashCode() % 2 == 0) {
            try {
                router.waitForMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            router.deliver("msg-" + threadName);
            AsyncTestContext.waitTimeoutMonitor()
                    .recordNotifyAll(lock, "message-router-lock");
        }
    }
}
