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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The correct twins of {@code ExecutorFeedWeavingTest}: a pool closed before the run ends, a
 * failing future kept and read, and a pool handed in from outside the run, all silent.
 */
@Tag("e2e")
class ExecutorFeedSparesCorrectUseTest {

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
        shared = Executors.newFixedThreadPool(2);
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 2, invocations = 5, includes = DetectorType.EXECUTOR_SHUTDOWN)
    void poolClosed() throws Exception {
        BEAN.closePool();
    }

    @AsyncTest(threads = 2, invocations = 5,
            includes = {DetectorType.EXECUTOR_SHUTDOWN, DetectorType.FUTURE_IGNORED})
    void failingSubmitInspectedOnAPoolHandedIn() throws InterruptedException {
        BEAN.keepFailingSubmit(shared);
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            assertTrue(findings.violationsFrom("ExecutorShutdown").isEmpty(),
                    "closePool shuts its pool down and awaits it, and the shared pool was made "
                            + "outside the run; a finding here reports correct code. Findings "
                            + "were: " + findings.violations());
            assertTrue(findings.violationsFrom("FutureIgnored").isEmpty(),
                    "keepFailingSubmit keeps the future and reads the failure through get, so "
                            + "nothing was dropped. Findings were: " + findings.violations());
        } finally {
            findings.close();
            shared.shutdownNow();
        }
    }
}
