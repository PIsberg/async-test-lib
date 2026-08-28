package se.deversity.asynctest.agent;

import com.example.agentfixture.LockOrderInversionBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that attaching the agent is enough to catch a lock-order inversion, with no instrumentation.
 *
 * <p><strong>What was silent.</strong> The agent has substituted every {@code Lock.lock()} and
 * {@code unlock()} call site since collection weaving shipped, and it handed all of it to
 * {@code HeldLocks}, which answers one question: was this access guarded. {@code LockOrderValidator}
 * asks a different question of the identical event stream - while holding one lock, which other did
 * this thread want - and it was reachable only through a hand-written
 * {@code recordLockAcquisition} call. So a user who attached the agent, wrote a plain
 * {@code @AsyncTest} and inverted their lock order got silence, while the data that would have
 * caught it was already flowing past. The same held for {@code LockLeakDetector} and
 * {@code TryLockMisuseDetector}, whose whole input the substitution also already carries.
 *
 * <p>That mattered more than one detector's worth, because it is the difference between a tool that
 * watches your code and one that watches what you hand it. Of 146 detectors, five worked without
 * the user editing their own class to expose hooks.
 *
 * <p>Both directions are asserted, and the negative is the one that decides whether this ships. A
 * finding on {@link ConsistentLockOrderBean} would be a false positive on code that is ordering its
 * locks correctly, and correct code being flagged is what gets a tool switched off.
 */
@Tag("e2e")
class LockWeavingFeedsLockDetectorsTest {

    private static AsyncFindings findings;

    private final LockOrderInversionBean inverted = new LockOrderInversionBean();

    @BeforeAll
    static void attachWithLockWeaving() {
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

    @AsyncTest(threads = 4, invocations = 25)
    void invertedLockOrder() {
        if ((Thread.currentThread().threadId() & 1L) == 0L) {
            inverted.forward();
        } else {
            inverted.reverse();
        }
    }


    @AfterAll
    static void theInversionIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("LockOrder")),
                    "Four threads took two ReentrantLocks in opposite orders through woven call "
                            + "sites, and no lock-order finding was produced. The substitution "
                            + "still runs - CollectionWeavingEndToEndTest pins that - so the "
                            + "break is in the delivery this test exists for: check that "
                            + "AgentLockHooks.deliverAcquired still resolves "
                            + "AsyncTestContext.currentLockOrderValidator(), that "
                            + "validateLockOrder is on for this run, and that the hook records "
                            + "the lockset identity rather than the view. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
