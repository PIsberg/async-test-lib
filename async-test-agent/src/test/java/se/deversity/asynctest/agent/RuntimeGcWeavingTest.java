package se.deversity.asynctest.agent;

import com.example.agentfixture.RuntimeGcForcingCacheBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that {@code Runtime.getRuntime().gc()} is caught as {@code System.gc()} is (#1179): the same
 * full collection, reported whichever way it is spelled. {@code Runtime.gc} is an
 * {@code invokevirtual}, so it reaches the hook through a receiver entry in {@code GC_ENTRIES}, not the
 * static path {@link ExplicitGcWeavingTest} covers.
 */
@Tag("e2e")
class RuntimeGcWeavingTest {

    private static AsyncFindings findings;

    private final RuntimeGcForcingCacheBean cache = new RuntimeGcForcingCacheBean();

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

    @AsyncTest(threads = 2, invocations = 2, detectAll = true)
    void collectingExplicitlyInsideTheRun() {
        cache.evict();
    }

    @AfterAll
    static void theExplicitCollectionIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("ExplicitGc")),
                    "RuntimeGcForcingCacheBean called Runtime.getRuntime().gc() through a woven call "
                            + "site and nothing was reported. Check that GC_ENTRIES carries the "
                            + "Runtime.gc receiver entry and that AgentGcHooks.runtimeGc resolves the "
                            + "detector. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
