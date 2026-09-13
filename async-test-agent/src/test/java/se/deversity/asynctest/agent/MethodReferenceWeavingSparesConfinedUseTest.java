package se.deversity.asynctest.agent;

import com.example.agentfixture.ConfinedThroughMethodReferenceBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The negative half of {@link MethodReferenceWeavingTest}.
 *
 * <p>Confined builders and a balanced lock, reached through the same method references, must stay
 * silent. And a serializable method reference must still deserialize: its generated
 * {@code $deserializeLambda$} checks the implementation method against the one it was compiled
 * with, so a rewritten handle would make it throw. The weaver leaves serializable references
 * alone, and this is what would notice if it stopped.
 */
@Tag("e2e")
class MethodReferenceWeavingSparesConfinedUseTest {

    private static AsyncFindings findings;

    private final ConfinedThroughMethodReferenceBean confined =
            new ConfinedThroughMethodReferenceBean();

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
    void confinedUseThroughMethodReferences() {
        confined.appendThroughBoundReference();
        confined.appendThroughUnboundReference();
        confined.balanceALockThroughReferences();
        assertEquals("deserialized", confined.roundTripASerializableReference(),
                "a serializable method reference in a woven class must still deserialize");
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            for (String detector : List.of("StringBuilder", "LockLeak")) {
                assertFalse(findings.violations().stream()
                                .anyMatch(v -> v.detector().contains(detector)),
                        "ConfinedThroughMethodReferenceBean confines its builders and balances its "
                                + "lock, so a " + detector + " finding means the rewritten "
                                + "reference records something the original call did not do. "
                                + "Findings were: " + findings.violations());
            }
        } finally {
            findings.close();
        }
    }
}
