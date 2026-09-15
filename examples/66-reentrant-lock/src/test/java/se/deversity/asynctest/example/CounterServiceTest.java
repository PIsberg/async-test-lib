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
 * count ends at 1 rather than 0. The other threads' tryLock() calls time out.
 * When the run is analysed, every body has finished and the lock is still
 * taken by thread A: ReentrantLockDetector reads that from the lock itself.
 *
 * DETECTORS TRIGGERED:
 *   ReentrantLockDetector — primary: a lock still held after every body finished
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
        // The finding is the lock itself: once the rounds are over, service.lock is still taken
        // by the worker that leaked the hold, and ReentrantLockDetector asks the lock at analysis
        // (#589). The recorded acquire/release pair below is balanced, which is why counts alone
        // never showed this leak. The other workers' tryLock timeouts are handled - they back off
        // - so they are printed as context, not reported; until #589 they were the finding, and
        // so was every handled timeout in correct code.
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
