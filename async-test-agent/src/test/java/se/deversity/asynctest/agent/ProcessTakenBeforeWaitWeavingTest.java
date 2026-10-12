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
 * A child process whose output the waiter takes before {@code waitFor} but reads only after it,
 * found through the real weaver with nothing recorded (#998).
 *
 * <p>The take used to count as a drain, so this shape was silent although it hangs exactly like an
 * untaken pipe. The woven getter now hands out a stream that notes its reads, and the wait is
 * judged on whether the waiter had read to the end first. {@code ProcessFeedSparesCorrectUseTest}
 * holds the twins: read to the end, then wait; and take, hand the stream to a drain thread, wait.
 */
@Tag("e2e")
class ProcessTakenBeforeWaitWeavingTest {

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
    void takeThenWaitThenRead() throws Exception {
        BEAN.takeThenWaitThenRead();
    }

    @AfterAll
    static void theWaitIsReported() {
        try {
            assertFalse(findings.violationsFrom("ProcessStreamStarvation").isEmpty(),
                    "ProcessUsingBean.takeThenWaitThenRead takes the merged output, waits, and "
                            + "only then reads it. Nothing was reported, so the woven getInputStream "
                            + "did not hand out an observed stream or the wait took the take for a "
                            + "drain. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
