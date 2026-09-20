package se.deversity.asynctest.agent;

import com.example.agentfixture.CrossMethodLoopWaitHandOffBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a cross-method predicate loop around a wait stays silent (#707).
 *
 * <p>When a while-loop encloses a method that calls {@code wait()}, the weaver must recognise
 * the enclosing loop and emit {@code loopBackEdge}, sparing this correct code from false positives.
 */
@Tag("e2e")
class CrossMethodMissedSignalWeavingTest {

    private static AsyncFindings findings;

    private final CrossMethodLoopWaitHandOffBean handOff = new CrossMethodLoopWaitHandOffBean();

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
    void pollingInACrossMethodPredicateLoopAfterTheSignalWasLost() throws InterruptedException {
        handOff.signalThenAwait();
    }

    @AfterAll
    static void theCrossMethodLoopIsNotReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("MissedSignal")),
                    "CrossMethodLoopWaitHandOffBean re-tests its predicate in an enclosing loop, "
                            + "so a notify it did not hear cannot strand it. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
