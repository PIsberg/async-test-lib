package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;
import se.deversity.asynctest.example.service.ServiceInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ServiceInitializer.
 *
 * ========================================================================
 * DETECTOR: LatchMisuseDetector
 * ========================================================================
 *
 * This test demonstrates how an extra countDown() call in an exception handler
 * allows a CountDownLatch to reach zero before all participating tasks finish.
 *
 * THE BUG:
 * ServiceInitializer starts N services concurrently and uses a CountDownLatch
 * to wait until all have either succeeded or failed. Each service task has:
 *
 *   try {
 *     startService(id);
 *   } catch (Exception e) {
 *     latch.countDown(); // BUG: called here...
 *   } finally {
 *     latch.countDown(); // ...and here — total 2 calls on failure
 *   }
 *
 * When service-1 fails, its task calls countDown() twice. For a 3-service
 * initialization the latch is created with count=3 but receives 4 calls:
 *   - service-0: 1 countDown (success)
 *   - service-1: 2 countDowns (failure)
 *   - service-2: 1 countDown (success, may not have started yet)
 *
 * The latch reaches zero too early — the caller believes all services are
 * ready when at most 2 have actually completed startup.
 *
 * WHY @Test PASSES:
 * A basic @Test can verify the return value but has no way to observe that
 * the latch count was exceeded relative to the initial count.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * LatchMisuseDetector tracks the initial count, countDown() calls, and
 * await() calls across invocations and reports when countDown() is called
 * more times than the latch was initialized with.
 *
 * DETECTORS TRIGGERED:
 * LatchMisuseDetector — standalone, instantiated directly in the test.
 *
 * FIX:
 * Remove countDown() from the catch block. Only the finally block should
 * call countDown() so exactly one call occurs per task.
 */
class ServiceInitializerTest {

    private ServiceInitializer initializer;
    private final LatchMisuseDetector detector = new LatchMisuseDetector();

    @BeforeEach
    void setUp() {
        initializer = new ServiceInitializer();
    }

    @AfterEach
    void tearDown() {
        initializer.shutdown();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — does not observe the extra countDown() calls
    // -------------------------------------------------------------------------

    @Test
    void testInitialize_allServicesStart_returnsTrue() throws Exception {
        // With only service-0 and service-2 (avoiding service-1 which throws),
        // this test passes because the latch still reaches zero correctly.
        // The double-countDown on service-1 is a silent logic error.
        boolean ready = initializer.initialize(3);
        // initialize() returns true — but at most 2 services actually started
        assertTrue(ready);
    }

    @Test
    void testInitialize_countDownLatch_correctUsage() throws Exception {
        // Demonstrate correct latch usage (no misuse)
        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            latch.countDown(); // exactly once per participant
        }
        assertEquals(0, latch.getCount());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the extra countDown() via LatchMisuseDetector
    // -------------------------------------------------------------------------

    /**
     * The bug: service-1 calls countDown() twice (catch + finally), so the
     * latch receives more countDown() calls than its initial count of 3.
     * LatchMisuseDetector reports this as a coordination logic error.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — LatchMisuseDetector will flag the extra countDowns
     * 3. Fix: remove latch.countDown() from the catch block
     */
    @Disabled("Remove @Disabled to see latch misuse detected by LatchMisuseDetector")
    @AsyncTest(threads = 4, invocations = 20)
    void testInitialize_concurrent_detectsExtraCountDowns() {
        int serviceCount = 3;
        CountDownLatch latch = new CountDownLatch(serviceCount);

        // Register the latch with the detector
        detector.registerLatch(latch, "service-startup-latch", serviceCount);

        // Simulate service-0 (succeeds): one countDown
        detector.recordCountDown(latch);

        // Simulate service-1 (fails): countDown in catch AND finally — two calls
        detector.recordCountDown(latch); // catch block
        detector.recordCountDown(latch); // finally block

        // Simulate service-2 (succeeds): one countDown
        detector.recordCountDown(latch);

        // Record that someone awaited this latch
        detector.recordAwait(latch);

        // Analyze — detector should report extra countDown() calls
        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertTrue(report.hasIssues(),
            "Expected extra countDown() calls to be detected.\n" + report);
        assertFalse(report.extraCountDowns.isEmpty(),
            "Expected extraCountDowns to be populated.\n" + report);
    }

    /**
     * Fixed version: countDown() is called exactly once per service task,
     * only in the finally block. No extra calls, no premature unblocking.
     */
    @Test
    void testInitialize_fixedCountDownOnce_noMisuseDetected() {
        int serviceCount = 3;
        CountDownLatch latch = new CountDownLatch(serviceCount);

        detector.registerLatch(latch, "fixed-startup-latch", serviceCount);

        // All three services call countDown() exactly once (fixed: only in finally)
        detector.recordCountDown(latch); // service-0 success
        detector.recordCountDown(latch); // service-1 failure — only finally fires
        detector.recordCountDown(latch); // service-2 success

        detector.recordAwait(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertFalse(report.hasIssues(),
            "No latch misuse expected when each task calls countDown() exactly once.\n" + report);
    }
}
