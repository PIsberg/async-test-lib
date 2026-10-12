package se.deversity.asynctest.agent;

import com.example.agentfixture.EventLoopBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A thread of a non-blocking scheduler that sleeps, started by a run's worker through woven code,
 * reported with nothing recorded (#991). The woven start is the attribution: it is why the
 * framework's scheduler packages belong in the agent's {@code includes=}.
 * {@code BlockingOnNonBlockingThreadSparesTest} holds the twins.
 */
@Tag("e2e")
class BlockingOnNonBlockingThreadWeavingTest {

    private static AsyncFindings findings;
    private static final EventLoopBean BEAN = new EventLoopBean();

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
    void sleepsOnANonBlockingThread() throws InterruptedException {
        BEAN.sleepOnANonBlockingThread();
    }

    @AfterAll
    static void theBlockingIsReported() {
        try {
            assertFalse(findings.violationsFrom("BlockingOnNonBlockingThread").isEmpty(),
                    "a NonBlocking thread the run started slept. Nothing was reported, so the woven "
                            + "sleep did not reach the detector through AgentConcurrencyUtilHooks.blocking "
                            + "or the thread was not attributed to the run. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
