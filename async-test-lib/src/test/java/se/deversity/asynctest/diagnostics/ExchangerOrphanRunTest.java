package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.FailOn;

import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orphaned exchange through the real runner, both directions (#585).
 *
 * <p>An untimed orphan never lets its round finish, so inside an {@code @AsyncTest} the finding
 * can only surface on the timeout path: the runner interrupts the workers, the odd caller's
 * {@code InterruptedException} leaves its start with nothing that ended it, and the timeout
 * message names the detector. That is the claim {@code examples/48-exchanger-misuse} makes, and
 * this pins it. The twin is the fix: odd callers of a timed exchange that handle the timeout
 * complete every round and pass a {@code failOn = LOW} gate.
 */
@E2E
class ExchangerOrphanRunTest {

    private static final String MULTIPLIER_PROPERTY = "async-test.timeout.multiplier";

    private String previousMultiplier;

    @BeforeEach
    void pinTheTimeoutBudget() {
        // CI stretches every budget with ASYNC_TEST_TIMEOUT_MULTIPLIER; the orphan fixture
        // depends on its budget actually expiring, so pin it for this test only.
        previousMultiplier = System.getProperty(MULTIPLIER_PROPERTY);
        System.setProperty(MULTIPLIER_PROPERTY, "1.0");
    }

    @AfterEach
    void restore() {
        if (previousMultiplier == null) {
            System.clearProperty(MULTIPLIER_PROPERTY);
        } else {
            System.setProperty(MULTIPLIER_PROPERTY, previousMultiplier);
        }
    }

    @Test
    void anOddCallerLeftInAnUntimedExchangeIsNamedByTheTimeout() {
        Events tests = run(OrphanFixture.class);

        Throwable thrown = tests.failed().stream()
                .findFirst()
                .flatMap(e -> e.getPayload(TestExecutionResult.class))
                .flatMap(TestExecutionResult::getThrowable)
                .orElse(null);
        assertNotNull(thrown, "three callers of an untimed exchange cannot all return");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(message.contains("ExchangerDetector"),
                "the odd caller's start was never ended, so the timeout must name the detector. "
                        + "Message was: " + message);
    }

    @Test
    void oddCallersThatHandleTheirTimeoutPassTheGate() {
        Events tests = run(HandledTimeoutFixture.class);

        assertEquals(0, tests.failed().count(),
                () -> "every caller left its exchange, one of them through a handled timeout, "
                        + "which is the fix and not a finding: " + tests.failed().list());
        assertEquals(1, tests.succeeded().count(), "the fixture must have run");
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    /** Three workers on one untimed exchanger: two pair, the third waits for good. */
    static class OrphanFixture {

        static final Exchanger<String> EXCHANGER = new Exchanger<>();

        @AsyncTest(threads = 3, invocations = 1, timeoutMs = 500, detectAll = false,
                detectDeadlocks = false, detectExchangerIssues = true,
                failOn = FailOn.LOW, licenseMockMode = true)
        void oddCaller() throws InterruptedException {
            ExchangerDetector detector = AsyncTestContext.exchangerDetector();
            detector.registerExchanger(EXCHANGER, "odd-exchanger");
            detector.recordExchangeStart(EXCHANGER, "odd-exchanger");
            String received = EXCHANGER.exchange("payload");   // the third never returns
            detector.recordExchangeComplete(EXCHANGER, "odd-exchanger", received);
        }
    }

    /** The same three workers on a timed exchange; the one left over gives up and says so. */
    static class HandledTimeoutFixture {

        static final Exchanger<String> EXCHANGER = new Exchanger<>();

        @AsyncTest(threads = 3, invocations = 3, timeoutMs = 20_000, detectAll = false,
                detectDeadlocks = false, detectExchangerIssues = true,
                failOn = FailOn.LOW, licenseMockMode = true)
        void oddCallerGivesUp() throws InterruptedException {
            ExchangerDetector detector = AsyncTestContext.exchangerDetector();
            detector.registerExchanger(EXCHANGER, "timed-exchanger");
            detector.recordExchangeStart(EXCHANGER, "timed-exchanger");
            try {
                String received = EXCHANGER.exchange("payload", 200, TimeUnit.MILLISECONDS);
                detector.recordExchangeComplete(EXCHANGER, "timed-exchanger", received);
            } catch (TimeoutException noPartner) {
                detector.recordTimeout(EXCHANGER);
            }
        }
    }
}
