package se.deversity.asynctest.agent;

import com.example.agentfixture.SleepingOutsideLockBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The negative half of {@link SleepInLockWeavingTest}, and the one that decides whether the static
 * substitution can ship at all.
 *
 * <p>{@link SleepingOutsideLockBean} performs exactly the same {@code Thread.sleep} through exactly
 * the same substituted call site, with the lock released first. If the detector reported here it
 * would be reporting the sleep rather than the holding, and every rate limiter, back-off loop and
 * poll in a woven codebase would light up.
 *
 * <p>That population is not comparable to the buggy one. Sleeping is ordinary; sleeping while
 * holding a lock is the mistake. A substitution that cannot tell them apart is worse than no
 * substitution, which is why {@code AgentSleepHooks} records nothing unless
 * {@code HeldLocks.anyHeld()} is true.
 */
@Tag("e2e")
class SleepInLockWeavingSparesSleepOutsideLockTest {

    private static AsyncFindings findings;

    private final SleepingOutsideLockBean outsideLock = new SleepingOutsideLockBean();

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
    void sleepingAfterReleasingTheMonitor() throws InterruptedException {
        outsideLock.process();
    }

    @AfterAll
    static void sleepOutsideALockIsNotReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("SleepInLock")),
                    "SleepingOutsideLockBean releases the monitor before sleeping, so no caller "
                            + "queues behind another and there is nothing to report. A finding "
                            + "here means the hook is recording the sleep rather than the "
                            + "holding, which would flag every rate limiter and back-off loop in "
                            + "a woven codebase - a far larger population than the bug. Check "
                            + "that AgentSleepHooks still guards on HeldLocks.anyHeld(). "
                            + "Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
