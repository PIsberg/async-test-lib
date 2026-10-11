package se.deversity.asynctest.agent;

import com.example.agentfixture.ExecutorUsingBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An executor the code creates and leaks, and a failing task whose future it drops, found through
 * the real weaver with nothing recorded.
 *
 * <p>{@code ExecutorFeedSparesCorrectUseTest} runs the closing and inspecting twins of the same
 * fixture in a class of their own, so each class's findings belong to its own rows.
 */
@Tag("e2e")
class ExecutorFeedWeavingTest {

    private static AsyncFindings findings;
    private static ExecutorService shared;
    private static final ExecutorUsingBean BEAN = new ExecutorUsingBean();

    @BeforeAll
    static void attachWithCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
        // Made here, on the JUnit thread outside any run, so it is no run's to shut down.
        shared = Executors.newFixedThreadPool(2);
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 2, invocations = 5, includes = DetectorType.EXECUTOR_SHUTDOWN)
    void poolLeftRunning() throws Exception {
        BEAN.leakPool();
    }

    @AsyncTest(threads = 2, invocations = 5, includes = DetectorType.FUTURE_IGNORED)
    void failingSubmitDiscarded() {
        BEAN.dropFailingSubmit(shared);
    }

    @AfterAll
    static void bothAreReported() {
        try {
            assertFalse(findings.violationsFrom("ExecutorShutdown").isEmpty(),
                    "ExecutorUsingBean.leakPool makes a pool through Executors on a run thread and "
                            + "leaves it running. Nothing was reported, so the woven factory did "
                            + "not declare it: check the Executors rows in "
                            + "CollectionAccessWeaver.CONCURRENCY_ENTRIES and "
                            + "AgentConcurrencyUtilHooks.created. Findings were: "
                            + findings.violations());
            assertFalse(findings.violationsFrom("FutureIgnored").isEmpty(),
                    "dropFailingSubmit discards the future of a task that throws. Nothing was "
                            + "reported, so the POP after submit did not reach "
                            + "submitResultDiscarded: check its whenResultDiscarded row. Findings "
                            + "were: " + findings.violations());
        } finally {
            findings.close();
            ExecutorUsingBean.LEFT_RUNNING.forEach(ExecutorService::shutdownNow);
            shared.shutdownNow();
        }
    }
}
