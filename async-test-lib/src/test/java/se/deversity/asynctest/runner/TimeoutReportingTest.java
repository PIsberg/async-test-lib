package se.deversity.asynctest.runner;
import se.deversity.asynctest.E2E;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.BeforeEachInvocation;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A timeout is one event and must be reported once.
 *
 * <p>The pre-round deadline check in {@code ConcurrencyRunner.execute} throws an error that
 * {@code timeoutError} has already reported — listeners notified, thread dump printed,
 * detector reports flushed. That error then landed in the same method's
 * {@code catch (AssertionError)}, where its message satisfied {@code isTimeoutLike} and sent
 * it through {@code timeoutError} a second time, so a single timeout produced two
 * {@code onTimeout} callbacks and two copies of every report.
 */
@E2E
class TimeoutReportingTest {

    private static final String MULTIPLIER_PROPERTY = "async-test.timeout.multiplier";

    private final AtomicInteger timeouts = new AtomicInteger();
    private final AsyncTestListener countingListener = new AsyncTestListener() {
        @Override
        public void onTimeout(long timeoutMs) {
            timeouts.incrementAndGet();
        }
    };

    private String previousMultiplier;

    @BeforeEach
    void pinTheTimeoutBudget() {
        // CI sets ASYNC_TEST_TIMEOUT_MULTIPLIER to stretch every budget; the fixture below
        // depends on its budget being consumed, so pin the multiplier for this test only.
        previousMultiplier = System.getProperty(MULTIPLIER_PROPERTY);
        System.setProperty(MULTIPLIER_PROPERTY, "1.0");
        AsyncTestListenerRegistry.register(countingListener);
    }

    @AfterEach
    void restore() {
        AsyncTestListenerRegistry.unregister(countingListener);
        if (previousMultiplier == null) {
            System.clearProperty(MULTIPLIER_PROPERTY);
        } else {
            System.setProperty(MULTIPLIER_PROPERTY, previousMultiplier);
        }
    }

    @Test
    void aTimedOutTestReportsExactlyOneTimeout() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(DeadlineExhaustionFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(1).succeeded(0).failed(1));
        assertEquals(1, timeouts.get(),
                "one timeout must produce one onTimeout callback, not one per reporting path");
    }

    /**
     * Burns the per-test budget in {@code @BeforeEachInvocation}, which runs inside the
     * invocation loop but outside any round's own {@code await}. The rounds themselves
     * complete instantly, so the budget runs out at the top of a later round — the
     * pre-round deadline check, which is the path that reported twice.
     */
    static class DeadlineExhaustionFixture {

        @BeforeEachInvocation
        void burnBudget() throws InterruptedException {
            Thread.sleep(120);
        }

        @AsyncTest(threads = 1, invocations = 6, timeoutMs = 200,
                detectDeadlocks = false, licenseMockMode = true)
        void instantBody() {
            // Intentionally empty: the deadline is consumed by burnBudget(), not here.
        }
    }
}
