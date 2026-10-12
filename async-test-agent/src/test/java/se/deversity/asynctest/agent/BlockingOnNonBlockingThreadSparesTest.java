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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The twins of {@code BlockingOnNonBlockingThreadWeavingTest} (#991): an ordinary thread the run
 * starts and that sleeps the same way, and a non-blocking thread sleeping all through the run but
 * started by a thread that is no worker, which the run must not claim.
 */
@Tag("e2e")
class BlockingOnNonBlockingThreadSparesTest {

    private static AsyncFindings findings;
    private static final EventLoopBean BEAN = new EventLoopBean();
    private static final AtomicBoolean STOP = new AtomicBoolean();
    private static Thread foreign;

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
        foreign = BEAN.keepSleepingOnANonBlockingThread(STOP);
    }

    @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.BLOCKING_ON_NON_BLOCKING_THREAD)
    void sleepsOnAnOrdinaryThread() throws InterruptedException {
        BEAN.sleepOnAnOrdinaryThread();
    }

    @AfterAll
    static void nothingIsReported() throws InterruptedException {
        try {
            STOP.set(true);
            foreign.join(5_000);
            assertTrue(findings.violationsFrom("BlockingOnNonBlockingThread").isEmpty(),
                    "an ordinary thread may block, and a non-blocking thread the run never saw is not "
                            + "its finding: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
