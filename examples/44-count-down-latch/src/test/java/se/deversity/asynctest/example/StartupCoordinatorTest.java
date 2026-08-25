package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.StartupCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for StartupCoordinator.
 *
 * ========================================================================
 * DETECTOR: CountDownLatchDetector
 * ========================================================================
 *
 * THE BUG:
 * StartupCoordinator creates a CountDownLatch(3) but only calls countDown()
 * when quickMode == false. Any thread that calls initialize(true) skips the
 * countDown(), so the latch never reaches zero. waitForStartup() then blocks
 * until the 2-second timeout expires and returns false.
 *
 * WHY @Test PASSES:
 * A single-threaded test always calls initialize(false) and countDown() is
 * invoked exactly as expected for that invocation. The latch state is consistent
 * because there is no concurrent access.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads each calling initialize(true) 50 times, countDown() is never
 * invoked. CountDownLatchDetector registers the latch, tracks countDown() calls,
 * and reports that the latch count never reached zero.
 *
 * FIX:
 * Always call latch.countDown() regardless of quickMode, ensuring every
 * registered party decrements the latch.
 */
class StartupCoordinatorTest {

    private StartupCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new StartupCoordinator();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testInitialize_normalMode_decrementsLatch() throws Exception {
        // Single-threaded normal mode: countDown() is called
        coordinator.initialize(false);
        assertEquals(2, coordinator.getRemainingCount(),
                "One call to initialize(false) should decrement latch from 3 to 2");
    }

    @Test
    void testInitialize_quickMode_latchNotDecremented() {
        // Single-threaded quick mode: countDown() is skipped — the bug is visible
        // but only as a return value check, not as a hang
        coordinator.initialize(true);
        assertEquals(3, coordinator.getRemainingCount(),
                "initialize(true) skips countDown() — latch stays at 3");
    }

    @Test
    void testWaitForStartup_afterThreeNormalInits_returnsTrue() throws Exception {
        coordinator.initialize(false);
        coordinator.initialize(false);
        coordinator.initialize(false);
        assertTrue(coordinator.waitForStartup(),
                "Three normal initializations should release the latch");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With all threads using quickMode == true, countDown() is never called.
     * CountDownLatchDetector detects that the registered latch count never
     * reaches zero across all invocations.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: always call latch.countDown() in initialize()
     */
    @Disabled("Remove @Disabled to see the bug detected by CountDownLatchDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCountDownLatchIssues = true, failOn = FailOn.LOW)
    void testInitialize_concurrent_detectsMissingCountDown() {
        // Register the latch with the detector before using it
        AsyncTestContext.countDownLatchMonitor()
                .registerLatch(coordinator.getLatch(), "startup-latch", 3);

        // All threads use quickMode=true — countDown() is never called
        coordinator.initialize(true);

        // Detector will observe: latch was registered with count=3,
        // but recordCountDown() was never called → reports the issue
    }
}
