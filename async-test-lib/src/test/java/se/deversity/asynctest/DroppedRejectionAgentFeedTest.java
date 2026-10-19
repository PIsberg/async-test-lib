package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.report.Violation;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A woven submission the executor rejects is recorded with {@code ThreadPoolMonitor}, so a
 * rejection the calling code catches and drops is reported (#1055).
 *
 * <p>A rejection that propagates fails the test on its own; one the caller swallows loses the task
 * silently, and before the feed nothing reported it ({@code AgentFeedOverlapAuditTest} row 4). The
 * bodies make the woven call by hand, {@code AgentConcurrencyUtilHooks.execute} in place of
 * {@code Executor.execute}.
 */
class DroppedRejectionAgentFeedTest {

    private static final Runnable SLOW = () -> {
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    private static List<Violation> threadPoolFindings(RejectedExecutionHandler policy) throws Throwable {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), policy);
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestRunner.run(AsyncTestConfig.builder().threads(2).invocations(3)
                    .includes(new DetectorType[] {DetectorType.THREAD_POOL}).timeoutMs(60_000)
                    .licenseMockMode(true).build(), () -> {
                for (int i = 0; i < 4; i++) {
                    try {
                        AgentConcurrencyUtilHooks.execute(pool, SLOW);
                    } catch (RejectedExecutionException dropped) {
                        // the bug: the task is lost and nobody hears of it
                    }
                }
            });
            return findings.violationsFrom("ThreadPool");
        } finally {
            pool.shutdownNow();
        }
    }

    /** The report text a finding carries; the message is only its header. */
    private static String text(Violation violation) {
        return violation.message() + " " + violation.attributes().get("report");
    }

    @Test
    void aRejectedSubmissionTheCallerDropsIsReported() throws Throwable {
        List<Violation> found = threadPoolFindings(new ThreadPoolExecutor.AbortPolicy());
        assertTrue(found.stream().anyMatch(v -> text(v).contains("tasks rejected")),
                "a saturated pool rejected woven submissions the body swallowed; ThreadPoolMonitor must"
                        + " report it with no recordTaskRejected call. Found: " + found);
        // The feed knows nothing of the pool's bounds, so no rule that compares against one may
        // fire: declaring zeros once made it report "All 0 threads busy" (#501).
        assertFalse(found.stream().anyMatch(v -> text(v).contains("threads busy")),
                "a pool known only through its rejections has no declared bounds: " + found);
    }

    @Test
    void aPoolThatRunsOverflowOnTheCallerRejectsNothing() throws Throwable {
        List<Violation> found = threadPoolFindings(new ThreadPoolExecutor.CallerRunsPolicy());
        assertFalse(found.stream().anyMatch(v -> text(v).contains("tasks rejected")),
                "CallerRunsPolicy rejects nothing: " + found);
    }
}
