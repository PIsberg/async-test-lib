package se.deversity.asynctest.agent;

import com.example.agentfixture.RunBlockingBean;
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
 * A Kotlin {@code runBlocking} entered on a coroutine dispatcher worker, reported through the woven
 * call site with nothing recorded (#1007). {@code RunBlockingSparesTheCallerTest} holds the twin.
 */
@Tag("e2e")
class RunBlockingWeavingTest {

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
    void runBlockingInsideACoroutine() throws InterruptedException {
        BEAN.runBlockingOnACoroutineWorker();
    }

    @AfterAll
    static void theRunBlockingIsReported() {
        try {
            assertFalse(findings.violationsFrom("BlockingOnNonBlockingThread").isEmpty(),
                    "a coroutine worker the run started entered runBlocking. Nothing was reported, so "
                            + "the call site was not woven (check RunBlockingWeaver.OWNER) or the hook "
                            + "did not reach the detector. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
