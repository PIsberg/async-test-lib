package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.report.Violation;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code new CompletableFuture<>()} in woven code is handed to
 * {@code CompletableFutureCompletionLeakDetector} as it is built, so one left pending on an
 * exception path is reported with no record call (#1054).
 *
 * <p>The detector already learns of a completion from the future itself, through a
 * {@code whenComplete} callback; what the recording API needed the test for was the creation. The
 * bodies make the call the agent inserts after the constructor by hand,
 * {@code AgentConstructionHooks.completableFutureConstructed}.
 */
class PendingFutureAgentFeedTest {

    private static List<Violation> leakFindings(boolean completeOnFailure) throws Throwable {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestRunner.run(AsyncTestConfig.builder().threads(2).invocations(3)
                    .includes(new DetectorType[] {DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS})
                    .timeoutMs(60_000).licenseMockMode(true).build(), () -> {
                CompletableFuture<String> future = new CompletableFuture<>();
                AgentConstructionHooks.completableFutureConstructed(future);
                try {
                    throw new IllegalStateException("backend down");
                } catch (IllegalStateException e) {
                    if (completeOnFailure) {
                        future.completeExceptionally(e);
                    }
                    // otherwise the bug: nobody waiting on the future will ever hear back
                }
            });
            return findings.violationsFrom("CompletableFutureCompletionLeak");
        }
    }

    @Test
    void aFutureLeftPendingOnTheExceptionPathIsReported() throws Throwable {
        List<Violation> found = leakFindings(false);
        assertFalse(found.isEmpty(),
                "a woven future the exception path never completed must be reported with no"
                        + " recordFutureCreated call");
    }

    @Test
    void aFutureCompletedOnEveryPathIsNot() throws Throwable {
        List<Violation> found = leakFindings(true);
        assertTrue(found.isEmpty(), "completeExceptionally completes the future: " + found);
    }
}
