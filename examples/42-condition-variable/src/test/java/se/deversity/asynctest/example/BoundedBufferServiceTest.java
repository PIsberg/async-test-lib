package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.BoundedBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for BoundedBufferService.
 *
 * ========================================================================
 * DETECTOR: ConditionVariableDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * BoundedBufferService uses signal() on both the notFull and notEmpty
 * conditions. With a single producer and single consumer this works by
 * coincidence — only one thread waits per condition. With multiple producers
 * and consumers, signal() may wake the wrong thread type (another consumer
 * when a producer should proceed), leaving threads stranded despite the
 * buffer having capacity.
 *
 * WHY @Test PASSES:
 * Sequential put/take pairs never block because the buffer is never full or
 * empty at the same time when accessed by a single thread. signal() is called
 * but has no visible effect — no other thread is waiting.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads concurrently call put() and take(). ConditionVariableDetector
 * records each await() and signal() call on the registered Condition objects.
 * When it sees signal() used with multiple waiters on the same condition, it
 * reports the potential missed-wakeup pattern.
 *
 * DETECTORS TRIGGERED:
 *   ConditionVariableDetector — primary: signal() used with multiple waiters
 *
 * FIX: replace signal() with signalAll() on both conditions.
 */
class BoundedBufferServiceTest {

    private BoundedBufferService service;

    @BeforeEach
    void setUp() {
        service = new BoundedBufferService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testPutAndTake_singleThread_works() throws InterruptedException {
        service.put("item-1");
        String result = service.take();
        assertEquals("item-1", result);
    }

    @Test
    void testSize_afterPut() throws InterruptedException {
        service.put("a");
        service.put("b");
        assertEquals(2, service.size());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes signal() vs signalAll() bug
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see signal() misuse detected by ConditionVariableDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectConditionVariableIssues = true, failOn = FailOn.LOW)
    void testBuffer_concurrent_detectsMissedSignal() throws InterruptedException {
        var notEmpty = service.getNotEmpty();
        var notFull  = service.getNotFull();

        // Register both conditions with the detector
        AsyncTestContext.get().conditionMonitor()
                .registerCondition(notEmpty, "not-empty");
        AsyncTestContext.get().conditionMonitor()
                .registerCondition(notFull, "not-full");

        // Alternate between producing and consuming based on thread id parity
        if (Thread.currentThread().getId() % 2 == 0) {
            // Producer path
            AsyncTestContext.get().conditionMonitor()
                    .recordSignal(notFull, "not-full", false); // signal() = isSignalAll:false
            service.put("item-" + Thread.currentThread().getId());
        } else {
            // Consumer path
            AsyncTestContext.get().conditionMonitor()
                    .recordSignal(notEmpty, "not-empty", false); // signal() = isSignalAll:false
            if (service.size() > 0) {
                String item = service.take();
                assertNotNull(item, "taken item must not be null");
            }
        }
    }
}
