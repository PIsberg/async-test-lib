package se.deversity.asynctest.agent;

import com.example.agentfixture.ReadThenUnrelatedStoreBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A woven get whose value is only logged, then a set from an unrelated size, must stay silent: the
 * stored value is not built from the read, so no update is lost (#1187). The positive control in
 * {@link AtomicDerivedStoreWeavingEndToEndTest} shows the same rule still reports a real lost update.
 */
@Tag("e2e")
class AtomicUnrelatedStoreWeavingEndToEndTest {

    private static AsyncFindings findings;

    private final ReadThenUnrelatedStoreBean bean = new ReadThenUnrelatedStoreBean();

    @BeforeAll
    static void attachWithFieldWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true");
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void readLoggedThenStoredFromAnUnrelatedSize() {
        bean.sample();
    }

    @AfterAll
    static void noLostUpdateIsReported() {
        try {
            findings.assertNotReported("AtomicNonAtomicUpdate");
        } finally {
            findings.close();
        }
    }
}
