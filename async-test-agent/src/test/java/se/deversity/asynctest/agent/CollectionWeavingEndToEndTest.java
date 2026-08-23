package se.deversity.asynctest.agent;

import com.example.agentfixture.DelegatedStateBean;
import com.example.agentfixture.GuardedDelegatedStateBean;
import com.example.agentfixture.SuperCallingMapBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.report.Violation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the capability {@code collections=true} adds, in both directions.
 *
 * <p>The corpus eval measured three documented-not-thread-safe classes producing no finding at all,
 * and they had one thing in common: their state lives in a JDK collection behind a final field, so
 * there is no field instruction to weave and the racing write happens where the agent never looks.
 * This test is that shape reduced to a fixture, and its guarded twin is the reason the mode is
 * safe to offer: a lock the test never declared still silences it, because the agent weaves the
 * monitor instruction alongside.
 *
 * <p>Driven through {@code @AsyncTest} rather than raw threads, unlike its field-weaving sibling,
 * because the hook resolves the detector from the live invocation context. That also means the
 * assertion runs on the path a user reads: a {@link Violation} on the report, not a detector
 * queried directly.
 */
@Tag("e2e")
class CollectionWeavingEndToEndTest {

    private static AsyncFindings findings;

    private final DelegatedStateBean unguarded = new DelegatedStateBean();
    private final GuardedDelegatedStateBean guarded = new GuardedDelegatedStateBean();

    @BeforeAll
    static void attachWithCollectionWeaving() {
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
    void unguardedDelegatedState() {
        unguarded.record("key");
    }

    @AsyncTest(threads = 4, invocations = 25)
    void guardedDelegatedState() {
        guarded.record("key");
    }

    @Test
    @DisplayName("a super.get() call keeps its INVOKESPECIAL dispatch and does not recurse")
    void superCallsAreLeftAlone() {
        SuperCallingMapBean bean = new SuperCallingMapBean();
        bean.put("key", "value");

        assertEquals("value", bean.get("key"),
                "SuperCallingMapBean.get delegates with super.get. Substituting that call would "
                        + "turn it into a virtual call, dispatch back into this override and "
                        + "recurse until the stack ran out, which is what a StackOverflowError "
                        + "here means: check that CollectionAccessWeaver still restricts every "
                        + "substitution with onVirtualCall().");
    }

    @Test
    @DisplayName("weaving preserves program semantics: the map still ends up with what was put in it")
    void substitutionDoesNotChangeWhatTheProgramComputes() {
        DelegatedStateBean bean = new DelegatedStateBean();
        for (int i = 0; i < 100; i++) {
            bean.record("key-" + (i % 7));
        }
        assertEquals(7, bean.size(),
                "each collection call was replaced by a hook that records and then performs the "
                        + "original operation. A wrong count here means a substitution dropped or "
                        + "reordered the call rather than delegating it.");
    }

    private static boolean namesCollection(String type) {
        return findings.violations().stream()
                .filter(v -> v.detector().contains("SharedCollection"))
                .anyMatch(v -> String.valueOf(v.attributes().get("report")).contains(type));
    }

    @AfterAll
    static void bothDirectionsHold() {
        try {
            // The message is the headline; the collection this finding is about is named in the
            // rendered report the violation carries, which is also what the user reads.
            boolean reportedOnUnguarded = namesCollection("HashMap");
            boolean reportedOnGuarded = namesCollection("TreeMap");

            assertTrue(reportedOnUnguarded,
                    "collections=true was supplied, so the HashMap inside DelegatedStateBean "
                            + "should have been recorded as read and written from four threads "
                            + "with no lock held. Nothing was reported, which means the "
                            + "substitution is not reaching SharedCollectionDetector: check that "
                            + "CollectionAccessWeaver's table still matches Map.get and Map.put, "
                            + "that AgentCollectionHooks resolves the invocation context, and that "
                            + "detectSharedCollections is on. Findings were: "
                            + findings.violations());

            assertFalse(reportedOnGuarded,
                    "GuardedDelegatedStateBean touches its TreeMap only inside a synchronized "
                            + "block on itself, and never declares that lock. The agent weaves the "
                            + "MONITORENTER, so the lockset must see a lock held across every "
                            + "access and report nothing. A finding here is a false positive on "
                            + "correct code, which is what makes collection weaving unsafe to "
                            + "ship: check that AsyncTestAgent still applies "
                            + "FieldAccessWeaver.visitor(false) when only collections=true is set. "
                            + "Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
