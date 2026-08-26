package se.deversity.asynctest.runner;

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

import java.util.Map;
import java.util.WeakHashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round timeout must say what the detectors saw, not only that the clock ran out.
 *
 * <p><strong>Why this exists.</strong> The {@code failOn} gate is success-path-only: a run
 * that timed out never reaches it, so the failure the reader gets is "Test timed out after
 * Nms. Possible deadlock, starvation, or visibility issue." The detector reports are printed
 * to stderr on the way out, but in a parallel reactor build that is hundreds of interleaved
 * lines away from the failure, and nothing in the failure says whether any detector had an
 * answer at all. Twenty-one example demonstrations named a detector in their {@code @Disabled}
 * reason and then failed on a timeout instead, and there was no way to tell from the failure
 * which of them had a finding waiting and which did not (issue #363).
 *
 * <p>Both directions are pinned here. Naming findings that exist is half the contract; saying
 * plainly that there were none is the other half, because "the run hung and no enabled
 * detector modelled it" is the answer that sends a reader somewhere else.
 */
@E2E
class RoundTimeoutFindingSummaryTest {

    private static final String MULTIPLIER_PROPERTY = "async-test.timeout.multiplier";

    private String previousMultiplier;

    @BeforeEach
    void pinTheTimeoutBudget() {
        // CI stretches every budget with ASYNC_TEST_TIMEOUT_MULTIPLIER; both fixtures below
        // depend on their budget actually expiring, so pin it for this test only.
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
    void aTimedOutRunNamesTheDetectorsThatHadAFinding() {
        String message = failureMessageOf(TimesOutHoldingAFindingFixture.class);

        assertTrue(message.contains("WeakHashMapSharedDetector"),
                "The timeout must name the detector that had a finding. Without it the reader "
                        + "is told the clock ran out and has to go looking through stderr to "
                        + "find out whether anything was detected. Message was: " + message);
    }

    @Test
    void aTimedOutRunWithNothingToReportSaysSo() {
        String message = failureMessageOf(TimesOutWithNothingToReportFixture.class);

        assertTrue(message.contains("No enabled detector produced a finding"),
                "A timeout with no findings must say so, rather than leaving the reader to "
                        + "assume a silent report is a missing one. Message was: " + message);
    }

    private static String failureMessageOf(Class<?> fixture) {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();

        Throwable thrown = tests.failed().stream()
                .findFirst()
                .flatMap(e -> e.getPayload(TestExecutionResult.class))
                .flatMap(TestExecutionResult::getThrowable)
                .orElse(null);
        assertNotNull(thrown, "the fixture must fail: its body outlives timeoutMs");
        String message = thrown.getMessage();
        assertNotNull(message, "the timeout error must carry a message");
        return message;
    }

    /**
     * Records a shared {@link WeakHashMap} from both threads, then hangs past the budget.
     * The recording is what makes the finding deterministic: two threads on one WeakHashMap
     * is the whole of {@code WeakHashMapSharedDetector}'s rule, so the finding is present
     * before the sleep and does not depend on the scheduler.
     */
    static class TimesOutHoldingAFindingFixture {

        static final Map<Object, Object> SHARED = new WeakHashMap<>();

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 300, detectAll = false,
                detectDeadlocks = false, detectWeakHashMapShared = true,
                failOn = FailOn.LOW, licenseMockMode = true)
        void recordsThenHangs() throws InterruptedException {
            AsyncTestContext.weakHashMapSharedDetector()
                    .recordAccess(SHARED, "shared-weak-cache", Thread.currentThread());
            Thread.sleep(30_000);       // cancelled by the round timeout; never runs to term
        }
    }

    /** Hangs past the budget with every detector off, so there is nothing to name. */
    static class TimesOutWithNothingToReportFixture {

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 300, detectAll = false,
                detectDeadlocks = false, failOn = FailOn.LOW, licenseMockMode = true)
        void justHangs() throws InterruptedException {
            Thread.sleep(30_000);       // cancelled by the round timeout
        }
    }
}
