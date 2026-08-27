package se.deversity.asynctest.agent;

import com.example.agentfixture.ConsistentLockOrderBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The negative half of {@link LockWeavingFeedsLockDetectorsTest}, and the one that gates the feed.
 *
 * <p>Routing the agent's lock substitutions into {@code LockOrderValidator} makes the detector fire
 * on code nobody instrumented. That is worth having only if it stays silent on code that orders its
 * locks correctly, because a tool that flags correct code gets switched off, and every finding it
 * ever makes is discounted from that point on.
 *
 * <p>{@link ConsistentLockOrderBean} takes {@code gamma} before {@code delta} on both of its call
 * paths, so no interleaving of four threads can produce a hold-and-wait cycle. Any finding here is
 * a false positive.
 *
 * <p>It has its own class rather than a second method in the positive test because a
 * {@code Violation} carries no test identifier: two fixtures sharing one {@code AsyncFindings}
 * scope cannot be told apart, and "it stayed silent" asserted that way would pass whether or not
 * the detector had run at all. One fixture per collection scope is what makes the silence mean
 * something.
 */
@Tag("e2e")
class LockWeavingDoesNotFlagConsistentOrderTest {

    private static AsyncFindings findings;

    private final ConsistentLockOrderBean consistent = new ConsistentLockOrderBean();

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
    void consistentLockOrder() {
        if ((Thread.currentThread().threadId() & 1L) == 0L) {
            consistent.forward();
        } else {
            consistent.alsoForward();
        }
    }

    @AfterAll
    static void correctOrderingIsNotReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("LockOrder")),
                    "ConsistentLockOrderBean takes gamma before delta on every path, so there is "
                            + "no hold-and-wait cycle for any interleaving to produce, and a "
                            + "lock-order finding here is a false positive on correct code. That "
                            + "is the failure mode that gets a tool switched off, which is why "
                            + "the agent's lock feed is gated on this assertion and not on the "
                            + "positive one alone. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
