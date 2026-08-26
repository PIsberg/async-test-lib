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
    @Disabled("Remove @Disabled to see carrier exhaustion detected by "
            + "VirtualThreadCarrierExhaustionDetector")
    // 64 threads, not 8, and one round rather than fifty. The detector fires when the number
    // of virtual threads blocked at once reaches the carrier count, which is
    // availableProcessors(): at 8 threads it fired on a 4-core CI runner and never on a
    // 16-core workstation, which is why this demonstration was recorded as "machine-dependent"
    // and baselined. 64 clears any machine anyone is likely to run this on, and it is the
    // honest bound rather than a guarantee. Fifty rounds of a 10ms sleep behind one monitor
    // took 4 seconds and timed the round out before the detector was consulted. See #362.
    @AsyncTest(threads = 64, invocations = 1, detectAll = false,
            detectVirtualThreadCarrierExhaustion = true, failOn = FailOn.LOW)
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
