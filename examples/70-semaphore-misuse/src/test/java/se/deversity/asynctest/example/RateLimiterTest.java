package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RateLimiter.
 *
 * ========================================================================
 * DETECTOR: SemaphoreMisuseDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * RateLimiter.executeRequest() acquires a semaphore permit and then calls the
 * task. The release() is placed after the task call with no finally block. Any
 * exception from the task permanently consumes the permit. After 5 failures all
 * permits are exhausted and every thread blocks indefinitely.
 *
 * WHY @Test PASSES:
 * A sequential test with a task that never throws goes through the happy path:
 * acquire → run → release. No permits are lost and the test finishes normally.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * Some threads submit tasks that throw. The release() is skipped on those paths.
 * SemaphoreMisuseDetector compares acquire vs release call counts and reports
 * the deficit as a permit-leak issue.
 *
 * DETECTORS TRIGGERED:
 *   SemaphoreMisuseDetector — primary: detects permit acquire/release imbalance
 *
 * FIX: always release in finally: sem.acquire(); try { r.run(); } finally { sem.release(); }
 */
class RateLimiterTest {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter(5);
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testExecuteRequest_successfulTask_releasesPermit() throws Exception {
        int before = limiter.availablePermits();
        limiter.executeRequest(() -> {/* no-op */});
        int after = limiter.availablePermits();
        assertEquals(before, after, "Permit count must be restored after successful execution");
    }

    @Test
    void testInitialPermits_matchConstructorArgument() {
        assertEquals(5, limiter.availablePermits(),
                "Limiter must start with 5 available permits");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes permit leak on exception
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see permit leak detected by SemaphoreMisuseDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, monitorSemaphore = true, failOn = FailOn.LOW)
    void testExecuteRequest_concurrent_detectsPermitLeak() {
        Semaphore sem = limiter.getSemaphore();
        String name = "rate-limiter-semaphore";

        // Register the semaphore with the detector
        AsyncTestContext.semaphoreMonitor()
                .registerSemaphore(sem, name, 5);

        AtomicInteger failCount = new AtomicInteger(0);

        try {
            // Record the acquire
            AsyncTestContext.semaphoreMonitor().recordAcquire(sem, name);

            // Submit a task that sometimes throws — simulating real failures
            limiter.executeRequest(() -> {
                if (Thread.currentThread().getId() % 3 == 0) {
                    throw new RuntimeException("simulated task failure");
                }
            });

            // Record the release only on the happy path
            AsyncTestContext.semaphoreMonitor().recordRelease(sem, name);

        } catch (RuntimeException e) {
            failCount.incrementAndGet();
            // BUG: release was skipped — permit permanently consumed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
