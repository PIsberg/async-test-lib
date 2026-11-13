package se.deversity.asynctest.agent;

import com.example.agentfixture.ConfinedCryptoBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code Mac}, {@code Signature} or {@code Cipher} that no two threads use at once is not
 * reported: one built per call, one per thread, and a shared one under its own monitor (#1321).
 */
@Tag("e2e")
class SharedCryptoWeavingSparesConfinedUseTest {

    private static AsyncFindings findings;

    private final ConfinedCryptoBean confined = new ConfinedCryptoBean();

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

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void perCallPerThreadAndGuardedPrimitives() throws GeneralSecurityException {
        confined.macPerCall();
        confined.macPerThread();
        confined.signaturePerCall();
        confined.cipherPerThread();
        confined.macUnderItsMonitor();
        confined.signatureUnderItsMonitor();
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("SharedStatefulCrypto")),
                    "ConfinedCryptoBean builds its primitives per call, keeps one per thread, or "
                            + "holds the shared one's monitor, so there is nothing to report. "
                            + "Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
