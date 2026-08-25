package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;
import se.deversity.asynctest.example.service.ServiceInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * await() calls, and reports when countDown() is called more times than the
 * latch was initialized with. ServiceInitializer.observeLatch hands it those
 * three facts from where they happen: the construction, every countDown, and
 * the await. failOn = FailOn.LOW turns the finding into a failed run.
 *
 * DETECTOR ENABLED HERE:
 * LatchMisuseDetector — more countDown() calls than the latch was built for.
 * It is the only one this demonstration switches on, so it is the only one that
 * can report.
 *
 * FIX:
 * Remove countDown() from the catch block. Only the finally block should
 * call countDown() so exactly one call occurs per task.
 */
class ServiceInitializerTest {

    private ServiceInitializer initializer;

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

    /**
     * The detector's positive direction, driven by the real service: three services, one of
     * them failing, four countDown() calls on a latch built for three.
     *
     * <p>The wait matters. initialize() returns as soon as the latch reaches zero, which is
     * after three calls; the fourth is the bug and lands a moment later, on a pool thread. That
     * gap is the defect, not a test artefact, so the test waits for the call it is asserting
     * about rather than assuming it has already happened.
     */
    @Test
    void testInitialize_realRun_recordsExtraCountDown() throws Exception {
        LatchMisuseDetector detector = new LatchMisuseDetector();
        CountDownLatch fourCalls = new CountDownLatch(4);
        wire(detector, fourCalls);

        initializer.initialize(3);

        assertTrue(fourCalls.await(5, TimeUnit.SECONDS),
                "expected 4 countDown() calls: three services, one of which counts twice");
        assertFalse(detector.analyze().extraCountDowns.isEmpty(),
                "a failing service counts down twice, so the latch gets 4 calls for a count of 3");
    }

    /**
     * And the other direction, on the same service with the extra call removed. A detector that
     * still reported here would be flagging correct latch use.
     */
    @Test
    void testInitializeFixed_realRun_isSilent() throws Exception {
        LatchMisuseDetector detector = new LatchMisuseDetector();
        CountDownLatch threeCalls = new CountDownLatch(3);
        wire(detector, threeCalls);

        initializer.initializeFixed(3);

        assertTrue(threeCalls.await(5, TimeUnit.SECONDS),
                "expected exactly one countDown() per service");
        assertFalse(detector.analyze().hasIssues(),
                "exactly one countDown() per task is correct use, not a finding");
    }

    private void wire(LatchMisuseDetector detector, CountDownLatch calls) {
        initializer.observeLatch(
                (latch, count) -> detector.registerLatch(latch, "service-startup-latch", count),
                latch -> {
                    detector.recordCountDown(latch);
                    calls.countDown();
                },
                detector::recordAwait);
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
    @AsyncTest(threads = 4, invocations = 20, detectAll = false,
            detectLatchMisuse = true, failOn = FailOn.LOW)
    void testInitialize_concurrent_detectsExtraCountDowns() throws Exception {
        // This demonstration used to hand-record four countDown() calls into a locally
        // constructed detector and assert on the result, without ever calling the service. It
        // proved the detector's arithmetic, which was never in doubt, and told the library
        // nothing: failOn had no finding to gate on. See issue #346.
        LatchMisuseDetector detector = AsyncTestContext.latchMisuseDetector();
        initializer.observeLatch(
                (latch, count) -> detector.registerLatch(latch, "service-startup-latch", count),
                detector::recordCountDown,
                detector::recordAwait);

        // BUG: service-1 throws, so its task counts down in the catch block and again in the
        // finally block. The latch was built for 3 and receives 4.
        initializer.initialize(3);
    }
}
