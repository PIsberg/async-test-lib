package se.deversity.asynctest.agent;

import com.example.agentfixture.ReadThenDerivedStoreBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Positive control for {@link AtomicUnrelatedStoreWeavingEndToEndTest}: a store built from the value
 * its own read returned is a lost update, and it must keep being reported (#1187).
 */
@Tag("e2e")
class AtomicDerivedStoreWeavingEndToEndTest {

    private static AsyncFindings findings;

    private final ReadThenDerivedStoreBean bean = new ReadThenDerivedStoreBean();

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
    void incrementFromAReadOfItself() {
        bean.increment();
    }

    @AfterAll
    static void lostUpdateIsReported() {
        try {
            findings.assertReported("AtomicNonAtomicUpdate");
        } finally {
            findings.close();
        }
    }
}
