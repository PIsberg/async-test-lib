package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ConfigManager.
 *
 * ========================================================================
 * DETECTOR: DoubleCheckedLockingDetector
 * ========================================================================
 *
 * THE BUG:
 * ConfigManager.getInstance() uses double-checked locking but the `instance`
 * field is not declared volatile. The JVM may reorder the write to `instance`
 * before the constructor body completes. A second thread passing the first
 * null-check can receive a non-null reference to a partially constructed object.
 *
 * WHY @Test PASSES:
 * A single thread calls getInstance() sequentially. The object is fully
 * constructed before the reference is ever read by the same thread.
 *
 * WHY @AsyncTest DETECTS:
 * DoubleCheckedLockingDetector.registerDCL() records the field with
 * isVolatile=false and the full DCL structure flags. During analysis it reports
 * the broken pattern because a non-volatile field in DCL is always unsafe.
 *
 * FIX:
 * Declare the field as `private static volatile ConfigManager instance`, or use
 * the initialization-on-demand holder idiom.
 */
class ConfigManagerTest {

    @BeforeEach
    void setUp() {
        ConfigManager.reset();
    }

    @AfterEach
    void tearDown() {
        ConfigManager.reset();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testGetInstance_singleThread_returnsSameInstance() {
        ConfigManager a = ConfigManager.getInstance();
        ConfigManager b = ConfigManager.getInstance();
        assertSame(a, b, "Multiple calls should return the same singleton instance");
    }

    @Test
    void testGetInstance_returnsConfigWithExpectedValues() {
        ConfigManager cfg = ConfigManager.getInstance();
        assertEquals("30", cfg.get("timeout"));
        assertEquals("3", cfg.get("retries"));
        assertEquals("false", cfg.get("debug"));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 threads concurrently calling getInstance(), the non-volatile field
     * allows the JVM to publish the reference before construction completes.
     * DoubleCheckedLockingDetector registers the DCL pattern with isVolatile=false
     * and reports it as broken.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: add `volatile` to the instance field in ConfigManager
     */
    @Disabled("Remove @Disabled to see the bug detected by DoubleCheckedLockingDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectDoubleCheckedLocking = true)
    void testGetInstance_concurrent_detectsBrokenDCL() {
        // Register the DCL pattern: field is not volatile, first-check + second-check
        // + synchronized block are all present — the classic broken DCL structure
        AsyncTestContext.doubleCheckedLockingMonitor()
                .registerDCL(
                        "ConfigManager.instance",
                        false,  // isVolatile — BUG: should be true
                        true,   // hasFirstCheck (outside synchronized)
                        true,   // hasSecondCheck (inside synchronized)
                        true    // insideSynchronized
                );

        // Read access that exercises the first (unsynchronized) check
        AsyncTestContext.doubleCheckedLockingMonitor()
                .recordAccess("ConfigManager.instance", true, false);

        ConfigManager cfg = ConfigManager.getInstance();
        assertNotNull(cfg, "getInstance() must never return null");
    }
}
