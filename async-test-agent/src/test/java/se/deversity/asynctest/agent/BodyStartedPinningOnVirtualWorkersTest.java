package se.deversity.asynctest.agent;

import com.example.agentfixture.PinningThreadStartingBean;
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
 * A virtual thread the body starts, pinning in every round, reported with the agent attached and
 * nothing recorded (#983), on virtual workers.
 *
 * <p>JFR reports every virtual thread in the JVM and carries no parent, so a pin on a thread the
 * body started used to be counted as unattributed. The woven {@code Thread.Builder.start}
 * attributes the child to the starting worker's run, and the pinning feed now takes that set as a
 * second source. {@code ForeignPinningIsNotAttributedTest} holds the other direction.
 */
@Tag("e2e")
class BodyStartedPinningOnVirtualWorkersTest {

    private static AsyncFindings findings;
    private static final PinningThreadStartingBean BEAN = new PinningThreadStartingBean();

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

    @AsyncTest(threads = 2, invocations = 3, useVirtualThreads = true,
               includes = DetectorType.VIRTUAL_THREAD_PINNING)
    void startsAThreadThatPins() throws InterruptedException {
        BEAN.startVirtualThreadThatPins();
    }

    @AfterAll
    static void theChildsPinIsReported() {
        try {
            assertFalse(findings.violationsFrom("VirtualThreadPinning").isEmpty(),
                    "Every round's body started a virtual thread that blocked in a fresh class "
                            + "initializer, and nothing was reported. The woven Thread.Builder.start "
                            + "should have put the child in the run's spawned set, which "
                            + "JfrPinningStream reads beside the workers. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
