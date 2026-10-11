package se.deversity.asynctest.agent;

import com.example.agentfixture.ProcessUsingBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A child process waited for before its output is read, found through the real weaver with
 * nothing recorded.
 *
 * <p>{@code ProcessFeedSparesCorrectUseTest} runs the correct twins of the same fixture in a class
 * of their own, so each class's findings belong to its own rows.
 */
@Tag("e2e")
class ProcessFeedWeavingTest {

    private static AsyncFindings findings;
    private static final ProcessUsingBean BEAN = new ProcessUsingBean();

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
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 2, invocations = 1, includes = DetectorType.PROCESS_STREAM_STARVATION)
    void waitThenRead() throws Exception {
        BEAN.waitThenRead();
    }

    @AfterAll
    static void theWaitIsReported() {
        try {
            assertFalse(findings.violationsFrom("ProcessStreamStarvation").isEmpty(),
                    "ProcessUsingBean.waitThenRead waits for a child whose merged output nobody "
                            + "had taken. Nothing was reported, so the woven waitFor did not reach "
                            + "AgentConcurrencyUtilHooks.processWaitFor: check the Process rows in "
                            + "CollectionAccessWeaver.CONCURRENCY_ENTRIES. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
