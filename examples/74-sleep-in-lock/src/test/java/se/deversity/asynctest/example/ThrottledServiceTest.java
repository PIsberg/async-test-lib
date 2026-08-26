package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ThrottledService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ThrottledService.
 *
 * ========================================================================
 * DETECTOR: SleepInLockDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (false confidence — single thread never contends)
 * - The same scenario with @AsyncTest FAILS (exposes the sleep-in-lock bug)
 *
 * THE BUG:
 * ThrottledService.processRequest() calls Thread.sleep(50) while holding
 * the intrinsic lock on {@code this}. Every concurrent caller must wait the
 * full 50 ms in the monitor queue. With 8 threads this means 7 threads are
 * always blocked — throughput collapses to ~20 req/s instead of parallelism.
 *
 * WHY @Test PASSES:
 * A single thread never contends for the lock; it processes requests one by one
 * with no waiting. The sleep is annoying but harmless in isolation.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * SleepInLockDetector.recordSleep() checks the calling thread's lock state.
 * When it finds Thread.sleep() called from within a synchronized block, it
 * records a SleepInLockEvent and the analysis report flags the violation.
 *
 * DETECTORS TRIGGERED:
 *   SleepInLockDetector — primary: catches Thread.sleep() inside synchronized blocks
 *
 * FIX: release the lock before sleeping; use ScheduledExecutorService or a
 *      proper RateLimiter (e.g. Guava) to throttle without blocking the monitor.
 */
class ThrottledServiceTest {

    private ThrottledService service;

    @BeforeEach
    void setUp() {
        service = new ThrottledService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_processesRequest() {
        service.processRequest("req-1");
        assertEquals(1, service.getRequestCount());
        assertTrue(service.getProcessed().contains("req-1"));
    }

    @Test
    void test_singleThread_multipleRequests() {
        service.processRequest("req-a");
        service.processRequest("req-b");
        assertEquals(2, service.getRequestCount());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes sleep-in-lock anti-pattern
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see sleep-in-lock detected by SleepInLockDetector")
    @AsyncTest(threads = 8, invocations = 2, useVirtualThreads = false, detectAll = false,
            detectSleepInLock = true, failOn = FailOn.LOW)

    void test_concurrent_detectsSleepInLock() {
        var detector = AsyncTestContext.get().sleepInLockDetector();
        detector.startMonitoring();

        // Recorded from inside the synchronized method, through the service's seam. The
        // detector asks the JVM which monitors the calling thread holds, so recording from
        // here - which is what this demonstration used to do - asks that question outside the
        // lock and always gets no. Nothing was ever recorded, and the round timed out with an
        // empty report. See issue #363.
        //
        // useVirtualThreads = false on the annotation is the other half, and it is not
        // decoration. The JVM answers that question through ThreadMXBean.getThreadInfo(id),
        // which does not report virtual threads, so with the default runner the seam fires and
        // the detector still sees no lock. Measured both ways on this subject: silent with the
        // default, "SLEEP-IN-LOCK PATTERNS DETECTED" with the line below.
        service.observeSleepInLock(() -> detector.recordSleep(50));

        // Two invocations, not twenty. Every caller queues behind a 50ms sleep, so eight
        // threads cost 400ms per invocation and twenty of them exceeded the five-second budget
        // before the detector could be analyzed. The collapse is the point and two rounds show
        // it; twenty only show it more slowly.
        service.processRequest("req-" + Thread.currentThread().getName());
    }
}
