package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.CounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for CounterService.
 *
 * ========================================================================
 * DETECTOR: ReentrantLockDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * CounterService.increment() acquires the ReentrantLock and then calls
 * validate() which acquires it a second time. The finally block releases the
 * lock only once, leaving the hold count at 1. After the first invocation the
 * lock is permanently held by the calling thread — all subsequent threads
 * calling increment() block indefinitely.
 *
 * WHY @Test PASSES:
 * A single thread re-enters the lock (perfectly legal for ReentrantLock) and
 * the test finishes before any deadlock manifests. The lock hold count stays
 * at 1 within the same thread until the method returns.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads compete to call increment(). Thread A enters and its lock hold
 * count ends at 1 rather than 0. Thread B then tries to lock() and blocks.
 * ReentrantLockDetector records acquire/release events and detects that
 * release count lags behind acquire count across invocations.
 *
 * DETECTORS TRIGGERED:
 *   ReentrantLockDetector — primary: detects unbalanced lock acquire/release
 *
 * FIX: Add a finally block inside validate() that calls lock.unlock(), or
 *      restructure validate() to operate without acquiring the lock.
 */
class CounterServiceTest {

    private CounterService service;

    @BeforeEach
    void setUp() {
        service = new CounterService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testIncrement_singleThread_returnsOne() {
        int result = service.increment();
        assertEquals(1, result, "First increment should return 1");
    }

    @Test
    void testIncrement_twoSequentialCalls_returnsTwo() {
        service.increment();
        // After first call the hold count is 1 (bug), but the same thread can
        // still re-enter — so sequential calls appear to work in single-thread
        int result = service.increment();
        assertEquals(2, result, "Second increment should return 2");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes lock hold-count imbalance
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see the leaked hold detected by ReentrantLockDetector")
    @AsyncTest(threads = 8, invocations = 2, detectAll = false,
            detectReentrantLockIssues = true, failOn = FailOn.LOW)

    void testIncrement_concurrent_detectsLockImbalance() throws InterruptedException {
        var detector = AsyncTestContext.reentrantLockMonitor();
        detector.registerLock(service.lock, "counter-service-lock");

        // Bounded, because increment() leaks a hold: it locks twice and unlocks once, so the
        // count never reaches zero and every later caller parks in lock() forever. Calling it
        // from all eight threads hung the round, and the round timed out before anything was
        // analyzed.
        //
        // The timeout is also the only thing this detector gates on. ReentrantLockReport.
        // hasIssues() is (timeouts or starvation); the acquire and release counts are recorded
        // and printed but never trip it, which is why the previous version's balanced pair
        // reported nothing even when it did run. A tryLock that expires on a counter whose
        // critical section is one increment is the leaked hold seen from outside, and is the
        // finding. See issue #363.
        if (service.lock.tryLock(100, TimeUnit.MILLISECONDS)) {
            try {
                detector.recordLockAcquired(service.lock, Thread.currentThread().getName());
                service.increment();
            } finally {
                detector.recordLockReleased(service.lock, Thread.currentThread().getName());
                service.lock.unlock();
            }
        } else {
            detector.recordLockTimeout(service.lock);
        }
    }
}
