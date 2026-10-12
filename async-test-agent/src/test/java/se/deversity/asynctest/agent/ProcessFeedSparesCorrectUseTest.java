package se.deversity.asynctest.agent;

import com.example.agentfixture.ProcessUsingBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The correct twins of {@code ProcessFeedWeavingTest}: a drain thread started before the wait,
 * output read before the wait, and output discarded, all silent.
 */
@Tag("e2e")
class ProcessFeedSparesCorrectUseTest {

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
    void drainWhileWaiting() throws Exception {
        BEAN.drainWhileWaiting();
    }

    @AsyncTest(threads = 2, invocations = 1, includes = DetectorType.PROCESS_STREAM_STARVATION)
    void readThenWait() throws Exception {
        BEAN.readThenWait();
    }

    @AsyncTest(threads = 2, invocations = 1, includes = DetectorType.PROCESS_STREAM_STARVATION)
    void takeThenHandOffThenWait() throws Exception {
        BEAN.takeThenHandOffThenWait();
    }

    @AsyncTest(threads = 2, invocations = 1, includes = DetectorType.PROCESS_STREAM_STARVATION)
    void discardThenWait() throws Exception {
        BEAN.discardThenWait();
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            assertTrue(findings.violationsFrom("ProcessStreamStarvation").isEmpty(),
                    "each twin drains or discards the child's output before or while waiting. A "
                            + "finding here means a take was not seen: the drain thread's getter "
                            + "was not attributed to the run, or a getter row is missing. "
                            + "Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
