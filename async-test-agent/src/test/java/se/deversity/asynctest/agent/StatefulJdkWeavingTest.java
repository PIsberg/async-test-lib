package se.deversity.asynctest.agent;

import java.util.List;

import com.example.agentfixture.SharedStatefulJdkBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a cached {@code Calendar}, {@code StringBuilder} and {@code DecimalFormat} are caught.
 *
 * <p>Same shape as {@link SharedInstanceWeavingTest} in a second tranche of types. Each is
 * expensive or awkward to build, so it gets hoisted to a field, and the class quietly stops being
 * safe to call concurrently. Every one had a detector already and every one of those detectors was
 * reachable only through a hand-written {@code record} call.
 *
 * <p>All three subjects run in one class because a {@code Violation} names its detector, so the
 * assertions can be told apart even though the test method cannot. The negative direction needs its
 * own class, and has one: {@link StatefulJdkWeavingSparesConfinedUseTest}.
 */
@Tag("e2e")
class StatefulJdkWeavingTest {

    private static AsyncFindings findings;

    private final SharedStatefulJdkBean shared = new SharedStatefulJdkBean();

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
    void fourThreadsThroughOneOfEach() {
        shared.year();
        shared.append();
        shared.money();
    }

    @AfterAll
    static void eachSharedInstanceIsReported() {
        try {
            for (String detector : List.of("Calendar", "StringBuilder", "DecimalFormat")) {
                assertTrue(findings.violations().stream()
                                .anyMatch(v -> v.detector().contains(detector)),
                        "Four threads used one shared " + detector + " through a woven call site "
                                + "and nothing was reported. The substitution is what makes this "
                                + "visible without a record call, so check that "
                                + "CollectionAccessWeaver's SHARED_INSTANCE_ENTRIES still matches "
                                + "the call, that AgentSharedInstanceHooks resolves the detector "
                                + "from AsyncTestContext, and that the agent adds "
                                + "sharedInstanceSubstitutions. Findings were: "
                                + findings.violations());
            }
        } finally {
            findings.close();
        }
    }
}
