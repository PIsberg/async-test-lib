package se.deversity.asynctest.agent;

import com.example.agentfixture.UninterruptiblePermitsBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #1501: permits taken with {@code acquireUninterruptibly} or {@code drainPermits} and returned.
 * Neither call was woven, so the release that returned them was recorded with no acquisition before
 * it, and on a thread that had acquired before, that read as a HIGH over-release.
 *
 * <p>A class of its own because {@code selfAttach} is at most once per JVM and each test class gets
 * a fork of its own.
 */
@Tag("e2e")
class UninterruptiblePermitsWeavingTest {

    private static AsyncFindings findings;

    private final UninterruptiblePermitsBean bean = new UninterruptiblePermitsBean();

    @BeforeAll
    static void attachWithConcurrencyWeaving() {
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

    @AsyncTest(threads = 1, invocations = 10, detectAll = true)
    void anUninterruptibleAcquireIsAnAcquisition() throws InterruptedException {
        bean.usePermitUninterruptibly();
    }

    @AsyncTest(threads = 1, invocations = 10, detectAll = true)
    void drainedPermitsAreHeldUntilReturned() throws InterruptedException {
        bean.drainAndReturn();
    }

    @AfterAll
    static void returnedPermitsAreNotOverReleases() {
        try {
            assertFalse(findings.violations().stream().anyMatch(v -> v.detector().contains("Semaphore")),
                    "every permit taken was returned by the thread that took it; findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
