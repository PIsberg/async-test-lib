package se.deversity.asynctest.agent;

import com.example.agentfixture.IfWaitHandOffBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that {@code if (!ready) wait()} after a lost notify is caught with nothing recorded (#694).
 *
 * <p>{@code MissedSignalDetector} could only judge what a body said about its own waits and
 * notifies, because {@code Object} exposes neither its waiters nor whether a notify reached one.
 * Woven, the wait and the notify are seen under the monitor they both hold, and the loop around a
 * wait is seen as the backward jump that comes back over it.
 *
 * <p>The negative direction is {@link MissedSignalWeavingSparesPredicateLoopTest}: the same
 * signal and the same timed wait inside {@code while (!ready)}, which has to stay silent.
 */
@Tag("e2e")
class MissedSignalWeavingTest {

    private static AsyncFindings findings;

    private final IfWaitHandOffBean handOff = new IfWaitHandOffBean();

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
    void waitingBehindAnIfAfterTheSignalWasLost() throws InterruptedException {
        handOff.signalThenAwait();
    }

    @AfterAll
    static void theStrandedWaitIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("MissedSignal")),
                    "Four threads signalled and then waited behind an if, through woven call "
                            + "sites, and nothing was reported. The first notifyAll of each round "
                            + "finds no waiter and the last wait of the round receives none, so "
                            + "this needs MONITOR_ENTRIES to substitute Object.wait(long) and "
                            + "notifyAll, and AgentMonitorHooks to record both while the monitor "
                            + "is held. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
