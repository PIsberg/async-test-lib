package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Pins that a round timeout keeps the failures the completed workers already produced.
 *
 * <p><strong>Why this exists.</strong> When {@code latch.await} expired,
 * {@code runSingleInvocationRound} threw its "Invocation round timed out" error before ever
 * looking at the {@code failures} list. A round where some workers threw real assertion
 * errors and the rest hung therefore reported only a thread count — and the thrown failures
 * are usually the diagnosis, because a worker that died before the barrier is the most common
 * reason its peers never finished. The collected failures now ride along as suppressed
 * exceptions on the round-timeout error, which the timeout path preserves as the cause chain
 * of the "Test timed out after ..." error the user sees.
 */
class RoundTimeoutFailurePreservationTest {

    @Test
    void workerFailuresSurviveARoundTimeoutAsSuppressed() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(OneThrowsOneHangsFixture.class))
                .execute()
                .testEvents();

        Throwable thrown = tests.failed().stream()
                .findFirst()
                .flatMap(e -> e.getPayload(TestExecutionResult.class))
                .flatMap(TestExecutionResult::getThrowable)
                .orElse(null);
        assertNotNull(thrown, "the fixture must fail: one worker hangs past timeoutMs");

        assertTrue(findInChain(thrown, IllegalStateException.class, "deliberate worker failure"),
                "The IllegalStateException thrown by the worker that DID complete must be "
                        + "reachable from the reported error (via cause or suppressed). "
                        + "Dropping it leaves only a thread count where the diagnosis was. "
                        + "Chain was: " + render(thrown));
    }

    /** Depth-first search over cause and suppressed links for a throwable of {@code type}
     * whose message contains {@code fragment}. */
    private static boolean findInChain(Throwable t, Class<?> type, String fragment) {
        if (t == null) return false;
        if (type.isInstance(t) && t.getMessage() != null && t.getMessage().contains(fragment)) {
            return true;
        }
        for (Throwable s : t.getSuppressed()) {
            if (findInChain(s, type, fragment)) return true;
        }
        return findInChain(t.getCause(), type, fragment);
    }

    private static String render(Throwable t) {
        StringBuilder sb = new StringBuilder();
        render(t, sb, 0);
        return sb.toString();
    }

    private static void render(Throwable t, StringBuilder sb, int depth) {
        if (t == null || depth > 5) return;
        sb.append('\n').append("  ".repeat(depth))
          .append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        for (Throwable s : t.getSuppressed()) render(s, sb, depth + 1);
        render(t.getCause(), sb, depth + 1);
    }

    // ---- Fixture driven through JUnit-platform-testkit (nested classes are excluded
    // from direct surefire discovery, matching the FailOnGateTest idiom) ----

    static class OneThrowsOneHangsFixture {
        private final AtomicInteger arrivals = new AtomicInteger();

        /** First body execution throws immediately; the second sleeps far past the budget,
         * so the round times out with exactly one real failure already collected. The
         * detector include keeps setup light and the timeout path free of thread dumps. */
        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 3_000,
                includes = {DetectorType.SHARED_MESSAGE_DIGEST},
                licenseMockMode = true)
        void oneThrowsOneHangs() throws InterruptedException {
            if (arrivals.getAndIncrement() == 0) {
                throw new IllegalStateException("deliberate worker failure");
            }
            Thread.sleep(120_000);
        }
    }
}
