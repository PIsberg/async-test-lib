package se.deversity.asynctest.agent;

import com.example.agentfixture.LeakyCoordinationBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a leaked semaphore permit and a dropped queue offer are caught with no instrumentation.
 *
 * <p>These are the plumbing types. A test author instruments a domain object because they suspect
 * it; nobody instruments a {@code Semaphore} three layers down in the class under test, which is
 * why the detectors for them were unreachable in practice rather than merely inconvenient.
 *
 * <p>What is asserted is deliberately loose - that something was reported about coordination - and
 * the tight assertion is the negative one in
 * {@link CoordinationWeavingSparesCorrectUseTest}. These detectors report protocol misuse whose
 * exact wording depends on how the interleaving fell out, so pinning the phrasing would make the
 * test flaky without making it stronger.
 */
@Tag("e2e")
class CoordinationWeavingTest {

    private static AsyncFindings findings;

    private final LeakyCoordinationBean leaky = new LeakyCoordinationBean();

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

    @AsyncTest(threads = 4, invocations = 25)
    void permitsLeakAndOffersAreDropped() throws InterruptedException {
        leaky.useAPermit(true);
        leaky.enqueue("element");
    }

    @AfterAll
    static void theMisuseIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("Semaphore")
                                    || v.detector().contains("BlockingQueue")),
                    "Four threads leaked semaphore permits and dropped offers on a queue of "
                            + "capacity two, through woven call sites, and nothing was reported. "
                            + "The substitution is what makes this visible without a record call, "
                            + "so check that CollectionAccessWeaver's CONCURRENCY_ENTRIES still "
                            + "matches Semaphore.acquire and BlockingQueue.offer, that "
                            + "AgentConcurrencyUtilHooks resolves its detectors from "
                            + "AsyncTestContext, and that the agent adds "
                            + "concurrencySubstitutions. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
