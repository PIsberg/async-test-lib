package se.deversity.asynctest.agent;

import com.example.agentfixture.ConditionSignalHandOffBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The silent twin of {@link ConditionSignalWeavingTest} (#1255): the same lost signal and the same
 * timed await, inside a loop that re-tests the state after every wakeup.
 */
@Tag("e2e")
class ConditionSignalWeavingSparesLoopTest {

    private static AsyncFindings findings;

    private final ConditionSignalHandOffBean handOff = new ConditionSignalHandOffBean();

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

    @AsyncTest(threads = 4, invocations = 10, detectAll = true)
    void awaitingInALoopAfterTheSignalWasLost() throws InterruptedException {
        handOff.signalThenAwaitInALoop();
    }

    @AfterAll
    static void theLoopIsNotReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("ConditionVariable")),
                    "ConditionSignalHandOffBean re-tests its state around every await, so a signal "
                            + "it did not hear cannot strand it. A finding here means the back-edge "
                            + "was not delivered for a Condition.await: check that its entries in "
                            + "MONITOR_ENTRIES carry whenInsideLoop and that AgentMonitorHooks."
                            + "loopBackEdge reaches ConditionVariableDetector. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
