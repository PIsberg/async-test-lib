package se.deversity.asynctest.agent;

import com.example.agentfixture.HandledWaitExitBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #1501 part 1: predicate loops that leave without reaching their back-edge, on a timeout the
 * caller handles or on an interrupt, were reported HIGH as a lost signal by
 * {@code MissedSignalDetector} and {@code ConditionVariableDetector}. Their must-fire twins, the
 * same signals with an {@code if} around a wait whose timeout is ignored, are
 * {@link MissedSignalWeavingTest} and {@link ConditionSignalWeavingTest}.
 *
 * <p>A class of its own because {@code selfAttach} is at most once per JVM and each test class gets
 * a fork of its own.
 */
@Tag("e2e")
class HandledWaitExitWeavingTest {

    private static AsyncFindings findings;

    private final HandledWaitExitBean bean = new HandledWaitExitBean();

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

    @AsyncTest(threads = 2, invocations = 10, detectAll = true)
    void aTimedLoopThatReturnsOnItsTimeout() throws InterruptedException {
        bean.signalThenReturnOnTimeout();
    }

    @AsyncTest(threads = 2, invocations = 10, detectAll = true)
    void anAwaitLoopEndedByAnInterrupt() {
        bean.signalThenInterruptedAwait();
    }

    @AsyncTest(threads = 2, invocations = 10, detectAll = true)
    void aWaitLoopEndedByAnInterrupt() {
        bean.notifyThenInterruptedWait();
    }

    @AsyncTest(threads = 2, invocations = 10, detectAll = true)
    void aTimedWaitLoopThatReturnsAtItsDeadline() throws InterruptedException {
        bean.notifyThenDeadlineLoop();
    }

    @AfterAll
    static void noneOfTheLoopsIsReported() {
        try {
            assertFalse(findings.violations().stream().anyMatch(v -> v.detector().equals("MissedSignalDetector")
                            || v.detector().equals("ConditionVariableDetector")),
                    "every loop re-tests its state after each wakeup and leaves on a handled timeout "
                            + "or an interrupt; findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
