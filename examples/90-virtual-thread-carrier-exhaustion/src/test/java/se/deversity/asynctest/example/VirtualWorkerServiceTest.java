package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.VirtualWorkerService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates {@code VirtualThreadCarrierExhaustionDetector}.
 *
 * <p>The passing tests show the service works correctly with a single thread.
 * The disabled test shows carrier exhaustion: each concurrent invocation pins
 * a carrier thread inside the {@code synchronized} sleep, and when all carriers
 * are occupied no other virtual thread can run.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class VirtualWorkerServiceTest {

    private VirtualWorkerService service;

    @BeforeEach
    void setUp() {
        service = new VirtualWorkerService();
    }

    @Test
    void test_singleThread_processesRequest() {
        service.processRequest("req-1");
        assertTrue(service.getProcessedCount() >= 1);
    }

    @Test
    void test_singleThread_serviceIsNotNull() {
        assertNotNull(service);
    }

    /**
     * Remove {@code @Disabled} to see {@code VirtualThreadCarrierExhaustionDetector}
     * report that the carrier pool was exhausted.
     *
     * <p>The detector wraps the synchronized-sleep call with
     * {@code recordBlockingStart} / {@code recordBlockingEnd}. When the number
     * of concurrent blocking events reaches the carrier count (default:
     * {@code Runtime.availableProcessors()}), the detector flags exhaustion.
     */
    @Disabled("Remove @Disabled to see bug detected by VirtualThreadCarrierExhaustionDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectVirtualThreadCarrierExhaustion = true, failOn = FailOn.LOW)
    void test_concurrent_detectsCarrierExhaustion() {
        var detector = AsyncTestContext.virtualThreadCarrierExhaustionDetector();
        String reason = "synchronized-sleep";

        // Signal that this thread is about to pin a carrier.
        detector.recordBlockingStart(reason, Thread.currentThread());
        try {
            // BUG: synchronized + sleep pins the carrier thread.
            service.processRequest("req-" + Thread.currentThread().threadId());
        } finally {
            detector.recordBlockingEnd(reason, Thread.currentThread());
        }
    }
}
