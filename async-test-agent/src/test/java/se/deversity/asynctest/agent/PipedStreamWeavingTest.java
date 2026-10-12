package se.deversity.asynctest.agent;

import com.example.agentfixture.PipedStreamBean;
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
 * A piped stream pair written and read on one thread, found through the real weaver with nothing
 * recorded (#994).
 *
 * <p>The {@code PipedInputStream} javadoc warns that using both ends from one thread may deadlock
 * it: once the buffer fills, the write waits for a read the same thread never makes.
 * {@code PipedStreamSparesOneThreadPerEndTest} holds the twin, a thread per end.
 */
@Tag("e2e")
class PipedStreamWeavingTest {

    private static AsyncFindings findings;
    private static final PipedStreamBean BEAN = new PipedStreamBean();

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
    void bothEndsOnOneThread() throws Exception {
        BEAN.bothEndsOnOneThread();
    }

    @AfterAll
    static void bothEndsOnOneThreadIsReported() {
        try {
            assertFalse(findings.violationsFrom("ProcessStreamStarvation").isEmpty(),
                    "PipedStreamBean.bothEndsOnOneThread writes into a pipe and reads it on the same "
                            + "thread. Nothing was reported, so the woven write did not reach its pipe: "
                            + "check the piped rows in CollectionAccessWeaver and that java.io is open "
                            + "to the library. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
