package se.deversity.asynctest.agent;

import com.example.agentfixture.GcForcingCacheBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that an explicit {@code System.gc()} is caught, through the agent's static substitution.
 *
 * <p>This is the second user of the {@code invokestatic} path {@code Thread.sleep} opened, and the
 * reason that path was built as a mechanism rather than a special case: {@code System.gc()} cost
 * one table entry and one hook, not another visitor.
 *
 * <p>It needs no lockset. A sleep is only a bug while a lock is held, which is why
 * {@link SleepInLockWeavingTest} has two halves to introduce; an explicit collection is a
 * stop-the-world pause whoever asked for it, so the call site alone is the finding.
 *
 * <p>The counts are deliberately small. Every body execution here performs a real full collection,
 * so this runs four of them rather than the forty the sleep tests can afford - and the detector
 * records the first one, so more would buy nothing.
 *
 * <p>The negative direction is {@link ExplicitGcWeavingSparesGcFreeCodeTest}.
 */
@Tag("e2e")
class ExplicitGcWeavingTest {

    private static AsyncFindings findings;

    private final GcForcingCacheBean cache = new GcForcingCacheBean();

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

    @AsyncTest(threads = 2, invocations = 2)
    void collectingExplicitlyInsideTheRun() {
        cache.evict();
    }

    @AfterAll
    static void theExplicitCollectionIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("ExplicitGc")),
                    "GcForcingCacheBean called System.gc() through a woven call site and nothing "
                            + "was reported. System.gc() is an invokestatic, so it is reachable "
                            + "only through the static substitution path: check that GC_ENTRIES "
                            + "still carries the System.gc entry, that AsyncTestAgent still adds "
                            + "gcSubstitutions to the visitor list, and that AgentGcHooks.gc "
                            + "still resolves the detector through "
                            + "AsyncTestContext.currentExplicitGcDetector(). Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
