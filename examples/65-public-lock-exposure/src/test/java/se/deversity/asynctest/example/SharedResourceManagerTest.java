package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.SharedResourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SharedResourceManager.
 *
 * ========================================================================
 * DETECTOR: PublicLockExposureDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * SharedResourceManager.getLock() returns the internal ReentrantLock. A caller
 * that acquires it without a finally-guarded unlock holds the lock indefinitely.
 * Every thread blocked inside accessResource() starves.
 *
 * WHY @Test PASSES:
 * A single thread is never blocked by another caller holding the lock. The
 * resource is accessed and released before the test checks the result.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads concurrently call getLock() and publish the lock object externally.
 * PublicLockExposureDetector sees the lock object passed to recordObjectPublished()
 * and flags it as a lock that has escaped the class's encapsulation boundary.
 *
 * DETECTORS TRIGGERED:
 *   PublicLockExposureDetector — primary: detects lock published to external callers
 *
 * FIX: remove getLock(); keep the ReentrantLock private; expose only high-level
 *      domain methods that manage the lock internally.
 */
class SharedResourceManagerTest {

    private SharedResourceManager manager;

    @BeforeEach
    void setUp() {
        manager = new SharedResourceManager();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testAccessResource_singleThread_incrementsValue() {
        int result = manager.accessResource();
        assertEquals(1, result, "First access should return 1");
    }

    @Test
    void testAccessResource_multipleCalls_sequential() {
        manager.accessResource();
        manager.accessResource();
        int result = manager.accessResource();
        assertEquals(3, result, "Three sequential accesses should return 3");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes public lock exposure
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see exposed lock detected by PublicLockExposureDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectPublicLockExposure = true)
    void testAccessResource_concurrent_detectsExposedLock() {
        // Record the lock object being published externally — the core anti-pattern
        AsyncTestContext.publicLockExposureMonitor()
                .recordObjectPublished(manager.getLock(), "SharedResourceManager.getLock()");

        // Also record synchronized access on this service instance
        AsyncTestContext.publicLockExposureMonitor()
                .recordSynchronizedOnThis(manager, Thread.currentThread(),
                        "SharedResourceManager");

        // Access the resource normally
        int value = manager.accessResource();
        assertTrue(value > 0, "Resource value must be positive");
    }
}
