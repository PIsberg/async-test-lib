package se.deversity.asynctest.agent;

import com.example.agentfixture.RunBlockingBean;
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
 * The twin of {@code RunBlockingWeavingTest} (#1007): the same woven {@code runBlocking} call site,
 * entered on the run's own worker thread, an ordinary thread, where bridging blocking code to
 * suspending code is what {@code runBlocking} is for.
 */
@Tag("e2e")
class RunBlockingSparesTheCallerTest {

    private static AsyncFindings findings;
    private static final RunBlockingBean BEAN = new RunBlockingBean();

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

    @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.BLOCKING_ON_NON_BLOCKING_THREAD)
    void runBlockingOnAnOrdinaryThread() {
        BEAN.runBlockingOnTheCaller();
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            assertTrue(findings.violationsFrom("BlockingOnNonBlockingThread").isEmpty(),
                    "runBlocking on an ordinary thread is its intended use: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
