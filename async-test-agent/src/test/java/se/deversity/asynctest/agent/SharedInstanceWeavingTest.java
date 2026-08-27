package se.deversity.asynctest.agent;

import java.util.Date;

import com.example.agentfixture.SharedFormatterBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a {@link java.text.SimpleDateFormat} cached in a field is caught with no instrumentation.
 *
 * <p>Hoisting a formatter to a field because constructing one is expensive, and thereby making the
 * class unsafe to call concurrently, is one of the oldest bugs in Java. The library has had a
 * detector for it throughout, and that detector was reachable only through a hand-written
 * {@code recordFormat} call - so it found the bug only for a test author who already suspected it.
 * The agent substitutes the {@code format} call site, which is the one place the instance and the
 * calling thread are both in hand.
 *
 * <p>The negative direction is {@link SharedInstanceWeavingSparesConfinedUseTest}, in its own class
 * because a {@code Violation} carries no test identifier.
 */
@Tag("e2e")
class SharedInstanceWeavingTest {

    private static AsyncFindings findings;

    private final SharedFormatterBean shared = new SharedFormatterBean();

    @BeforeAll
    static void attachWithSharedInstanceWeaving() {
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
    void oneFormatterAcrossFourThreads() {
        shared.render(new Date());
    }

    @AfterAll
    static void theSharedFormatterIsReported() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("SimpleDateFormat")),
                    "Four threads formatted through one cached SimpleDateFormat via a woven call "
                            + "site, and nothing was reported. The substitution is what makes this "
                            + "visible without a record call, so check that "
                            + "CollectionAccessWeaver's SHARED_INSTANCE_ENTRIES still matches "
                            + "SimpleDateFormat.format(Date), that AgentSharedInstanceHooks "
                            + "resolves AsyncTestContext.currentSimpleDateFormatDetector(), and "
                            + "that the agent adds sharedInstanceSubstitutions when collections "
                            + "weaving is on. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
