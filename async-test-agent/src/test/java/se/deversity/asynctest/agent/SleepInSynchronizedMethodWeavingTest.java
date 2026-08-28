package se.deversity.asynctest.agent;

import com.example.agentfixture.SleepingInSynchronizedMethodBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a sleep inside a {@code synchronized} method is caught (#388).
 *
 * <p>This was filed as blocked. The reasoning was that {@code HeldLocks} would have to learn the
 * monitor, which needs a push on method entry and a pop on every exit including the exceptional
 * one - a handler and a branch, so new stack map frames, which {@code AsyncTestAgent}'s
 * {@code COMPUTE_MAXS, never COMPUTE_FRAMES} note rules out.
 *
 * <p>The lockset does not have to learn it. The weaver already knows at weave time that the
 * enclosing method is synchronized and what it locks, so it loads that monitor and calls a hook
 * that takes it. One more value on the stack, no branch, no handler.
 */
@Tag("e2e")
class SleepInSynchronizedMethodWeavingTest {

    private static AsyncFindings findings;

    private final SleepingInSynchronizedMethodBean bean = new SleepingInSynchronizedMethodBean();

    @BeforeAll
    static void attachWithStaticWeaving() {
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

    @AsyncTest(threads = 4, invocations = 10)
    void sleepingInsideASynchronizedMethod() throws InterruptedException {
        bean.process();
    }

    @AfterAll
    static void theSleepInTheSynchronizedMethodIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("SleepInLock")),
                    "Four threads slept inside a synchronized method and nothing was reported. "
                            + "This shape has no MONITORENTER to weave, so the finding depends "
                            + "entirely on the weaver naming the monitor itself: check that "
                            + "STATIC_ENTRIES still carries .whenSynchronized(...) for the sleep, "
                            + "and that SubstitutingMethodVisitor still loads the monitor - "
                            + "ALOAD 0 for an instance method - before calling "
                            + "AgentSleepHooks.sleepHoldingMonitor. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
