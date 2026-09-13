package se.deversity.asynctest.agent;

import com.example.agentfixture.SharedThroughMethodReferenceBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a woven JDK call made through a method reference is observed (#550).
 *
 * <p>{@code builder::append} and {@code lock::lock} compile to {@code invokedynamic}, and the
 * call is made from a hidden class the agent cannot weave. Probed on 2026-09-13 before the fix:
 * the method-reference form of a shared {@code StringBuilder} produced no finding while the
 * lambda form of the same append did. The weaver now points the lambda factory at the hook
 * instead of the JDK method. The negative direction, including a serializable reference that
 * must be left alone, is {@link MethodReferenceWeavingSparesConfinedUseTest}.
 */
@Tag("e2e")
class MethodReferenceWeavingTest {

    private static AsyncFindings findings;

    private final SharedThroughMethodReferenceBean shared = new SharedThroughMethodReferenceBean();

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
    void fourThreadsThroughMethodReferences() {
        shared.appendThroughBoundReference();
        shared.appendThroughUnboundReference();
        shared.leakALockThroughAReference();
    }

    @AfterAll
    static void eachCallIsReported() {
        try {
            for (String detector : List.of("StringBuilder", "LockLeak")) {
                assertTrue(findings.violations().stream()
                                .anyMatch(v -> v.detector().contains(detector)),
                        "The " + detector + " call was reached only through a method reference "
                                + "and nothing was reported. CollectionAccessWeaver must rewrite a "
                                + "LambdaMetafactory implementation handle that matches a table "
                                + "entry to the entry's hook. Findings were: "
                                + findings.violations());
            }
        } finally {
            findings.close();
        }
    }
}
