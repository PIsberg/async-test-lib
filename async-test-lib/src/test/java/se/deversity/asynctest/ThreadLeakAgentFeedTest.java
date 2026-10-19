package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.report.Violation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ThreadLeakDetector} hears about a thread the body starts through the woven
 * {@code Thread.start}, with no {@code recordThreadStart} call (#1056).
 *
 * <p>The bodies make the call the agent weaves by hand, {@code AgentThreadHooks.threadStart} in
 * place of {@code Thread.start()}. Before the feed, a thread the body started and never joined was
 * reported only by {@code DaemonThreadHygieneDetector}, for its daemon status
 * ({@code AgentFeedOverlapAuditTest} row 5); the agent module's audit runs the same shape woven.
 */
class ThreadLeakAgentFeedTest {

    private static List<Violation> threadLeakFindings(AsyncTestRunner.Body body) throws Throwable {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestRunner.run(AsyncTestConfig.builder().threads(2).invocations(3)
                    .includes(new DetectorType[] {DetectorType.THREAD_LEAKS}).timeoutMs(60_000).licenseMockMode(true).build(), body);
            return findings.violationsFrom("ThreadLeak");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aStartedThreadThatOutlivesTheBodyIsReported() throws Throwable {
        List<Violation> found = threadLeakFindings(() ->
                AgentThreadHooks.threadStart(new Thread(() -> pause(3_000), "outlives-the-body")));
        assertTrue(!found.isEmpty() && found.stream().anyMatch(v -> v.message().contains("outlives-the-body")
                        || String.valueOf(v.attributes()).contains("outlives-the-body")),
                "a thread started through the woven start and still alive at analysis must be reported"
                        + " by ThreadLeakDetector with no recordThreadStart call. Found: " + found);
    }

    @Test
    void aStartedThreadTheBodyJoinsIsNotReported() throws Throwable {
        List<Violation> found = threadLeakFindings(() -> {
            Thread thread = new Thread(() -> pause(20), "joined-by-the-body");
            AgentThreadHooks.threadStart(thread);
            AgentThreadHooks.threadJoin(thread);
        });
        assertFalse(found.stream().anyMatch(v -> v.message().contains("joined-by-the-body")
                        || String.valueOf(v.attributes()).contains("joined-by-the-body")),
                "a joined thread is not a leak: " + found);
    }
}
