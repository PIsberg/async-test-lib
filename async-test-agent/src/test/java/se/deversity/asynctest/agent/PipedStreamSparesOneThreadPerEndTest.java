package se.deversity.asynctest.agent;

import com.example.agentfixture.PipedStreamBean;
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
 * The twin of {@code PipedStreamWeavingTest}: the same pipe with its writer on a thread of its own,
 * the javadoc's own pattern, is not reported (#994).
 */
@Tag("e2e")
class PipedStreamSparesOneThreadPerEndTest {

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
    void oneThreadPerEnd() throws Exception {
        BEAN.oneThreadPerEnd();
    }

    @AfterAll
    static void oneThreadPerEndIsSilent() {
        try {
            assertTrue(findings.violationsFrom("ProcessStreamStarvation").isEmpty(),
                    "one thread writes and another reads: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
