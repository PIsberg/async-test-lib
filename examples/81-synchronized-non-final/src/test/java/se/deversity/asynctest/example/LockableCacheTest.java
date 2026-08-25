package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.LockableCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for LockableCache.
 *
 * ========================================================================
 * DETECTOR: SynchronizedNonFinalDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (single thread, no competing lock acquisitions)
 * - The same scenario with @AsyncTest FAILS (non-final lock silently changes)
 *
 * THE BUG:
 * LockableCache.put/get use synchronized(lockObject) but lockObject is a
 * non-final field. reassignLock() replaces it with a new Object instance at
 * any time. After reassignment, threads that locked the OLD object and threads
 * that lock the NEW object can be in the critical section simultaneously.
 *
 * WHY @Test PASSES:
 * A single thread never contends with itself — put/get run uninterrupted and
 * reassignLock() is only dangerous when called concurrently with put/get.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * SynchronizedNonFinalDetector.recordLockObject() records the actual monitor
 * instance used on each invocation for a given field ID. When it observes two
 * different Object instances for the same field, it flags a potential lock-swap.
 *
 * DETECTORS TRIGGERED:
 *   SynchronizedNonFinalDetector — primary: detects changing lock-object identity
 *
 * FIX: make lockObject final so it can never be reassigned.
 */
class LockableCacheTest {

    private LockableCache cache;

    @BeforeEach
    void setUp() {
        cache = new LockableCache();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, always correct
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_putAndGet_works() {
        cache.put("key1", "value1");
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    void test_singleThread_missingKey_returnsNull() {
        assertNull(cache.get("absent"));
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes non-final lock object vulnerability
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see non-final lock detected by SynchronizedNonFinalDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectSynchronizedNonFinal = true, failOn = FailOn.LOW)
    void test_concurrent_detectsNonFinalLock() {
        var detector = AsyncTestContext.get().synchronizedNonFinalDetector();

        // Record the current lock object. Interleaved reassignLock() calls cause
        // the identity to differ across invocations, which the detector flags.
        detector.recordLockObject(
            cache.getLockObject(),
            "LockableCache.lockObject",
            LockableCache.class
        );

        // Half the threads write; every other thread triggers a lock reassignment.
        String threadName = Thread.currentThread().getName();
        if (threadName.hashCode() % 2 == 0) {
            cache.reassignLock();
        }
        cache.put("key-" + threadName, "value-" + threadName);
    }
}
