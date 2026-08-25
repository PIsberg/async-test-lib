package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.CountDownLatchDetector;
import se.deversity.asynctest.example.service.StartupCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * Every thread calls initialize(true), so countDown() is never invoked, and every
 * thread's waitForStartup() gives up. CountDownLatchDetector reports the latch
 * whose await timed out.
 *
 * WHAT THE DETECTOR DOES NOT REPORT:
 * A registered latch that never reached zero, on its own. It should not: a latch
 * mid-flight looks exactly like that, and reporting it would flag every latch the
 * moment it is created. hasIssues() gates on a wait that gave up, or on more
 * countDown() calls than the latch was built for. This demonstration used to
 * register the latch, skip the countDown and stop there, which is why enabling it
 * reported nothing. See issue #346.
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

    /**
     * The detector's positive direction: quick mode never counts down, so the wait gives up,
     * and a wait that gave up is what this detector reports.
     */
    @Test
    void testCountDownLatchDetector_awaitTimedOut_reports() throws Exception {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        wire(detector);
        detector.registerLatch(coordinator.getLatch(), "startup-latch", 3);

        coordinator.initialize(true);   // BUG: no countDown
        assertFalse(coordinator.waitForStartup(20, TimeUnit.MILLISECONDS),
                "the latch is still at 3, so the wait must give up");

        assertTrue(detector.analyze().hasIssues(),
                "a wait that gave up on a latch that never reached zero is the finding");
    }

    /**
     * And the other direction, on the same coordinator with the bug avoided. A latch that
     * reaches zero and releases its waiter is a latch doing its job.
     */
    @Test
    void testCountDownLatchDetector_latchReleased_isSilent() throws Exception {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        wire(detector);
        detector.registerLatch(coordinator.getLatch(), "startup-latch", 3);

        coordinator.initialize(false);
        coordinator.initialize(false);
        coordinator.initialize(false);
        assertTrue(coordinator.waitForStartup(1, TimeUnit.SECONDS),
                "three countDown() calls release a latch built for three");

        assertFalse(detector.analyze().hasIssues(),
                "a latch that released its waiter is not a finding");
    }

    private void wire(CountDownLatchDetector detector) {
        CountDownLatch latch = coordinator.getLatch();
        coordinator.observeLatch(
                () -> detector.recordCountDown(latch),
                () -> detector.recordAwaitSuccess(latch),
                () -> detector.recordTimeout(latch));
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
    @AsyncTest(threads = 8, invocations = 10, detectAll = false,
            detectCountDownLatchIssues = true, failOn = FailOn.LOW)
    void testInitialize_concurrent_detectsMissingCountDown() throws Exception {
        CountDownLatchDetector monitor = AsyncTestContext.countDownLatchMonitor();
        CountDownLatch latch = coordinator.getLatch();
        coordinator.observeLatch(
                () -> monitor.recordCountDown(latch),
                () -> monitor.recordAwaitSuccess(latch),
                () -> monitor.recordTimeout(latch));
        monitor.registerLatch(latch, "startup-latch", 3);

        // All threads use quickMode=true, so countDown() is never called.
        coordinator.initialize(true);

        // And this is the step the demonstration used to skip. Registering a latch that never
        // reaches zero is not a finding, and should not be: a latch mid-flight looks the same.
        // The finding is a wait that gave up. See issue #346.
        assertFalse(coordinator.waitForStartup(20, TimeUnit.MILLISECONDS),
                "startup never completes, because quick mode never counts down");
    }
}
