package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.LegacyService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates {@code VirtualThreadPinningDetector}.
 *
 * <p>The passing tests verify that {@code fetchData} returns a result in a
 * single-threaded context. The disabled test shows the pinning problem: each
 * concurrent invocation on virtual test threads enters the {@code synchronized}
 * sleep, pinning its carrier thread for 10 ms, and the detector reports those
 * pinning events.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class LegacyServiceTest {

    private LegacyService service;

    @BeforeEach
    void setUp() {
        service = new LegacyService();
    }

    @Test
    void test_singleThread_fetchDataReturnsResult() {
        String result = service.fetchData("https://example.com/api");
        assertNotNull(result);
        assertTrue(result.contains("example.com"));
    }

    @Test
    void test_singleThread_fetchCountIncremented() {
        service.fetchData("https://example.com/data");
        assertTrue(service.getFetchCount() >= 1);
    }

    /**
     * Remove {@code @Disabled} to see {@code VirtualThreadPinningDetector}
     * report pinned carrier threads.
     *
     * <p>{@code recordPinningEvent} signals the detector that this thread is
     * about to block while holding a monitor (synchronized). After the call
     * completes, {@code recordUnpinEvent} is called. The detector correlates
     * start/end pairs and reports threads that were pinned during blocking ops.
     */
    @Disabled("Remove @Disabled to see bug detected by VirtualThreadPinningDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectVirtualThreadPinning = true, failOn = FailOn.LOW)
    void test_concurrent_detectsPinning() {
        var detector = AsyncTestContext.virtualThreadPinningDetector();
        Thread thread = Thread.currentThread();

        detector.startMonitoring();
        // Signal that this thread is entering a synchronized block with blocking.
        detector.recordPinningEvent(thread, "synchronized-fetchData");

        try {
            // BUG: synchronized method with sleep pins the carrier thread.
            service.fetchData("https://api.example.com/resource");
        } finally {
            detector.recordUnpinEvent(thread);
        }
    }
}
