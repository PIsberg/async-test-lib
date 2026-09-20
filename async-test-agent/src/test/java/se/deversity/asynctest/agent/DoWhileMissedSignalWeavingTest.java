package se.deversity.asynctest.agent;

import com.example.agentfixture.DoWhileWaitHandOffBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that {@code do { wait(); } while (!ready)} after a lost notify is caught as a missed signal (#707).
 *
 * <p>Waiting before testing the predicate is the missed-signal bug. The weaver must not emit
 * {@code loopBackEdge} for a jump whose target is the wait itself without an intervening
 * conditional predicate check.
 */
@Tag("e2e")
class DoWhileMissedSignalWeavingTest {

    private static AsyncFindings findings;

    private final DoWhileWaitHandOffBean handOff = new DoWhileWaitHandOffBean();

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
    void waitingInADoWhileLoopAfterTheSignalWasLost() throws InterruptedException {
        handOff.signalThenAwait();
    }

    @AfterAll
    static void theDoWhileWaitIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("MissedSignal")),
                    "DoWhileWaitHandOffBean waits before testing its predicate, so a lost notify "
                            + "leaves the wait stranded. The weaver must not treat this as a guarded "
                            + "predicate loop. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
