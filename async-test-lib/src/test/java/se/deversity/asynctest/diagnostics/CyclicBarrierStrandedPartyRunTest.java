package se.deversity.asynctest.diagnostics;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a barrier left a party short, with untimed waiters parked, is reported
 * when the round times out (#631).
 */
class CyclicBarrierStrandedPartyRunTest {

    private static final String MULTIPLIER_PROPERTY = "async-test.timeout.multiplier";
    private String previousMultiplier;

    @BeforeEach
    void pinTimeoutMultiplier() {
        previousMultiplier = System.getProperty(MULTIPLIER_PROPERTY);
        System.setProperty(MULTIPLIER_PROPERTY, "1.0");
    }

    @AfterEach
    void restoreTimeoutMultiplier() {
        if (previousMultiplier == null) {
            System.clearProperty(MULTIPLIER_PROPERTY);
        } else {
            System.setProperty(MULTIPLIER_PROPERTY, previousMultiplier);
        }
    }

    @Test
    void aPartyShortBarrierLeftWithUntimedWaitersIsNamedByTheTimeout() {
        Events tests = run(PartyShortFixture.class);

        Throwable thrown = tests.failed().stream()
                .findFirst()
                .flatMap(e -> e.getPayload(TestExecutionResult.class))
                .flatMap(TestExecutionResult::getThrowable)
                .orElse(null);
        assertNotNull(thrown, "two workers on a three-party barrier cannot complete the round");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(message.contains("CyclicBarrierDetector"),
                "the barrier was left a party short, so the timeout must name the detector. Message was: " + message);
    }

    @Test
    void allPartiesArrivingAndTrippingPassesCleanly() {
        Events tests = run(AllPartiesArrivedFixture.class);

        assertEquals(0, tests.failed().count(),
                () -> "all parties arrived and tripped, which must pass: " + tests.failed().list());
        assertEquals(1, tests.succeeded().count(), "the fixture must have run");
    }

    @Test
    void handledTimeoutFollowedByResetIsSilent() {
        Events tests = run(HandledTimeoutFixture.class);

        assertEquals(0, tests.failed().count(),
                () -> "each waiter handled its timeout and reset the barrier, so no issue: " + tests.failed().list());
        assertEquals(1, tests.succeeded().count(), "the fixture must have run");
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    /** Two workers on a three-party barrier: both wait, the third never arrives. */
    static class PartyShortFixture {

        static final CyclicBarrier BARRIER = new CyclicBarrier(3);

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 500, detectAll = false,
                detectDeadlocks = false, detectCyclicBarrierIssues = true,
                failOn = FailOn.LOW, licenseMockMode = true)
        void partyShort() throws Exception {
            CyclicBarrierDetector detector = AsyncTestContext.cyclicBarrierDetector();
            detector.registerBarrier(BARRIER, "short-barrier", 3);
            detector.recordArrival(BARRIER);
            detector.recordAwait(BARRIER);
            BARRIER.await();
        }
    }

    /** Two workers on a two-party barrier: both arrive, barrier trips and round succeeds. */
    static class AllPartiesArrivedFixture {

        static final CyclicBarrier BARRIER = new CyclicBarrier(2);

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 20_000, detectAll = false,
                detectDeadlocks = false, detectCyclicBarrierIssues = true,
                failOn = FailOn.LOW, licenseMockMode = true)
        void allPartiesArrive() throws Exception {
            CyclicBarrierDetector detector = AsyncTestContext.cyclicBarrierDetector();
            detector.registerBarrier(BARRIER, "healthy-barrier", 2);
            detector.recordArrival(BARRIER);
            detector.recordAwait(BARRIER);
            BARRIER.await();
            detector.recordBarrierComplete(BARRIER);
        }
    }

    /** Two workers on a three-party barrier, but using timed await and handling timeout. */
    static class HandledTimeoutFixture {

        static final CyclicBarrier BARRIER = new CyclicBarrier(3);

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 20_000, detectAll = false,
                detectDeadlocks = false, detectCyclicBarrierIssues = true,
                failOn = FailOn.LOW, licenseMockMode = true)
        void handledTimeout() throws Exception {
            CyclicBarrierDetector detector = AsyncTestContext.cyclicBarrierDetector();
            detector.registerBarrier(BARRIER, "timed-barrier", 3);
            detector.recordArrival(BARRIER);
            detector.recordAwait(BARRIER);
            try {
                BARRIER.await(100, TimeUnit.MILLISECONDS);
                detector.recordBarrierComplete(BARRIER);
            } catch (TimeoutException e) {
                detector.recordTimeout(BARRIER);
                detector.recordReset(BARRIER);
                BARRIER.reset();
            } catch (BrokenBarrierException ignored) {
                // peer party reset or timed out
            }
        }
    }
}
