package se.deversity.asynctest.agent;

import com.example.agentfixture.ConditionSignalHandOffBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that {@code if (!ready) condition.await(t)} after a lost signal is caught with nothing
 * recorded (#1255).
 *
 * <p>{@code ConditionVariableDetector} judged only what a body recorded, and its one finding was a
 * waiter the lock shows parked, so a timed await that runs out was silent. Woven, the await and
 * the signal are seen under the lock they both hold, and the loop around an await is the backward
 * jump that comes back over it, as for {@code Object.wait}.
 *
 * <p>The negative direction is {@link ConditionSignalWeavingSparesLoopTest}: the same signal and
 * the same timed await inside {@code while (!ready)}, which has to stay silent.
 */
@Tag("e2e")
class ConditionSignalWeavingTest {

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
    void awaitingBehindAnIfAfterTheSignalWasLost() throws InterruptedException {
        handOff.signalThenAwaitOnce();
    }

    @AfterAll
    static void theStrandedAwaitIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("ConditionVariable")),
                    "Four threads signalled a Condition and then awaited it behind an if, through "
                            + "woven call sites, and nothing was reported. The first signalAll of "
                            + "each round finds no waiter and the last await of the round receives "
                            + "none, so this needs MONITOR_ENTRIES to substitute Condition.await"
                            + "(long, TimeUnit) and signalAll, and AgentMonitorHooks to record both "
                            + "on ConditionVariableDetector. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
