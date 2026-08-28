package se.deversity.asynctest.agent;

import com.example.agentfixture.GcFreeCacheBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The negative direction of {@link ExplicitGcWeavingTest}: code that never asks for a collection
 * is not reported.
 *
 * <p>The subject sleeps, which is the <em>other</em> entry in the static substitution table. That
 * is the point of it. A test that only called {@code System.gc()} could never reveal a table whose
 * two static entries were bound to each other's hooks, because in that direction both bindings
 * produce the same answer. Exercising the neighbouring entry and requiring silence is what pins
 * them apart.
 *
 * <p>The sleep is outside any lock, so {@code SleepInLockDetector} has nothing to say about it
 * either.
 */
@Tag("e2e")
class ExplicitGcWeavingSparesGcFreeCodeTest {

    private static AsyncFindings findings;

    private final GcFreeCacheBean cache = new GcFreeCacheBean();

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
    void evictingWithoutForcingACollection() throws InterruptedException {
        cache.evict();
    }

    @AfterAll
    static void gcFreeCodeIsNotReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("ExplicitGc")),
                    "GcFreeCacheBean never calls System.gc(), so ExplicitGcDetector must stay "
                            + "silent. A finding here means the static substitution table is "
                            + "mis-bound - most likely the Thread.sleep entry reaching "
                            + "AgentGcHooks - which would report an explicit collection for every "
                            + "woven sleep in a codebase. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
