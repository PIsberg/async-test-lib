package se.deversity.asynctest.agent;

import com.example.agentfixture.LoopWaitHandOffBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a predicate loop around a woven wait stays silent (#694).
 *
 * <p>The twin of {@link MissedSignalWeavingTest}, and the direction that decides whether the weave
 * can be trusted: a consumer polling {@code while (!ready) wait(timeout)} after the last producer
 * finished records exactly the sequence the bug does, a notify nobody heard and then a wait that
 * ran out. A finding here would flag every bounded poll in a woven codebase. What separates the
 * two is the backward jump around the wait, which the weaver marks with
 * {@code AgentMonitorHooks.loopBackEdge()}.
 */
@Tag("e2e")
class MissedSignalWeavingSparesPredicateLoopTest {

    private static AsyncFindings findings;

    private final LoopWaitHandOffBean handOff = new LoopWaitHandOffBean();

    @BeforeAll
    static void attachWithMonitorWeaving() {
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

    @AsyncTest(threads = 4, invocations = 10, detectMissedSignals = true)
    void pollingInAPredicateLoopAfterTheSignalWasLost() throws InterruptedException {
        handOff.signalThenAwait();
    }

    @AfterAll
    static void thePredicateLoopIsNotReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("MissedSignal")),
                    "LoopWaitHandOffBean re-tests its predicate around every wait, so a notify it "
                            + "did not hear cannot strand it. A finding here means the back-edge "
                            + "was not delivered: check that SubstitutingMethodVisitor emits "
                            + "loopBackEdge in front of the jump that comes back over the wait, "
                            + "and that MissedSignalDetector.recordObservedLoopBackEdge confirms "
                            + "the woken wait. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
