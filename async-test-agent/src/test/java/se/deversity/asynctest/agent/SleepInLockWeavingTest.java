package se.deversity.asynctest.agent;

import com.example.agentfixture.SleepingUnderLockBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a sleep inside a lock is caught, through the agent's first static substitution.
 *
 * <p>This one needed a piece of the weaver that did not exist. The substitution rewrote
 * {@code invokevirtual} and {@code invokeinterface}; {@code Thread.sleep} is {@code invokestatic},
 * so the call the detector exists for was the one call it could not see. A static substitution is
 * simpler than a virtual one - no receiver on the stack, and no subtype dispatch, so the owner is
 * matched exactly - which is why the gap was work rather than risk.
 *
 * <p>The other half was already there. Whether a sleep is a bug depends entirely on whether a lock
 * was held, which no stack trace records and {@code Thread.holdsLock} can only answer about an
 * object you already named. {@code HeldLocks} has known it all along, fed by the woven
 * {@code MONITORENTER} and the substituted {@code Lock.lock()}, so the two halves only had to be
 * introduced.
 *
 * <p>The negative direction is {@link SleepInLockWeavingSparesSleepOutsideLockTest}, and it matters
 * more here than anywhere: rate limiting, back-off and polling are all sleeps, and they vastly
 * outnumber the bug.
 */
@Tag("e2e")
class SleepInLockWeavingTest {

    private static AsyncFindings findings;

    private final SleepingUnderLockBean underLock = new SleepingUnderLockBean();

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
    void sleepingWhileHoldingTheMonitor() throws InterruptedException {
        underLock.process();
    }

    @AfterAll
    static void theSleepUnderLockIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("SleepInLock")),
                    "Four threads slept inside a synchronized method through a woven call site and "
                            + "nothing was reported. Both halves have to be working: the static "
                            + "substitution must rewrite Thread.sleep, which needs the "
                            + "INVOKESTATIC branch in SubstitutionWrapper and an Entry.staticCall "
                            + "in STATIC_ENTRIES, and HeldLocks.anyHeld() must see the monitor the "
                            + "field weaver's MONITORENTER pushed. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
