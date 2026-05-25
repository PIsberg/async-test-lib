package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.HealthCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for HealthCheckService.
 *
 * ========================================================================
 * DETECTOR: ScheduledExecutorDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * HealthCheckService.startChecks() creates a ScheduledExecutorService but never
 * calls shutdown(). Each call leaks a background thread. Over many concurrent
 * invocations this drains the thread pool and leaves dangling threads after
 * the test suite finishes.
 *
 * WHY @Test PASSES:
 * A single call creates one thread. The JVM exits (or GC runs) before the
 * leaking thread causes observable harm in a short-lived test process.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads each call startChecks(), creating up to 8 executors across
 * invocations. ScheduledExecutorDetector calls checkShutdown() at the end of
 * the round and reports every executor that was registered but never shut down.
 *
 * DETECTORS TRIGGERED:
 *   ScheduledExecutorDetector — primary: detects executors never shut down
 *
 * FIX: implement AutoCloseable and call scheduler.shutdown() in close(), or
 *      expose stop() and call it in @AfterEach.
 */
class HealthCheckServiceTest {

    private HealthCheckService service;

    @BeforeEach
    void setUp() {
        service = new HealthCheckService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testStartChecks_singleThread_schedulerNotNull() {
        service.startChecks();
        ScheduledExecutorService scheduler = service.getScheduler();
        assertNotNull(scheduler, "Scheduler must be created by startChecks()");
        assertFalse(scheduler.isShutdown(), "Scheduler should be running after start");
        scheduler.shutdownNow(); // clean up manually in sequential test
    }

    @Test
    void testCheckCount_initiallyZero() {
        assertEquals(0, service.getCheckCount(),
                "Check count must be 0 before startChecks() is called");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes executor shutdown leak
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see executor leak detected by ScheduledExecutorDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectScheduledExecutorIssues = true)
    void testStartChecks_concurrent_detectsExecutorLeak() {
        // Start checks — creates a new executor and never shuts it down
        service.startChecks();

        ScheduledExecutorService scheduler = service.getScheduler();
        if (scheduler != null) {
            // Register the executor with the detector
            AsyncTestContext.scheduledExecutorMonitor()
                    .registerExecutor(scheduler, "health-check-scheduler", 1);

            // Record a scheduled task
            AsyncTestContext.scheduledExecutorMonitor()
                    .recordSchedule(scheduler, "health-check-scheduler", "health-check-task");

            // BUG: recordShutdown() is never called — detector flags the leak
        }

        assertTrue(service.getCheckCount() >= 0, "Check count must be non-negative");
    }
}
