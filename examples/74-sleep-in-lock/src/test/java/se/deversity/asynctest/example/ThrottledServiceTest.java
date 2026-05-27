package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
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
    @AsyncTest(threads = 8, invocations = 20, detectAll = false, detectSleepInLock = true)
    void test_concurrent_detectsSleepInLock() {
        var detector = AsyncTestContext.get().sleepInLockDetector();
        detector.startMonitoring();

        // Record the sleep that happens inside the synchronized block.
        // The detector checks whether the calling thread holds any monitor.
        detector.recordSleep(50);

        service.processRequest("req-" + Thread.currentThread().getName());
    }
}
