package se.deversity.asynctest.agent;

import com.example.agentfixture.SharedCryptoBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.report.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code Mac}, {@code Signature} or {@code Cipher} shared across threads is reported with the
 * agent attached (#1321).
 *
 * <p>One body per call site and the findings kept per test, so a finding is credited to the one
 * call that produced it.
 */
@Tag("e2e")
class SharedCryptoWeavingTest {

    private static AsyncFindings findings;
    private static final Map<String, List<Violation>> BY_TEST = new ConcurrentHashMap<>();
    private static int credited;

    private final SharedCryptoBean shared = new SharedCryptoBean();

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

    @AfterEach
    void creditFindingsToThisTest(TestInfo info) {
        List<Violation> all = findings.violations();
        BY_TEST.computeIfAbsent(info.getTestMethod().orElseThrow().getName(), k -> new ArrayList<>())
                .addAll(all.subList(credited, all.size()));
        credited = all.size();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void macUpdate() {
        shared.macUpdate();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void macDoFinal() {
        shared.macDoFinal();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void signatureUpdate() {
        shared.signatureUpdate();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void signatureSign() {
        shared.signatureSign();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void signatureVerify() {
        shared.signatureVerify();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void cipherUpdate() {
        shared.cipherUpdate();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void cipherDoFinal() {
        shared.cipherDoFinal();
    }

    @AfterAll
    static void eachCallSiteIsReported() {
        try {
            List<String> missed = new ArrayList<>();
            for (List<String> expected : List.of(
                    List.of("macUpdate", "Mac"),
                    List.of("macDoFinal", "Mac"),
                    List.of("signatureUpdate", "Signature"),
                    List.of("signatureSign", "Signature"),
                    List.of("signatureVerify", "Signature"),
                    List.of("cipherUpdate", "Cipher"),
                    List.of("cipherDoFinal", "Cipher"))) {
                boolean reported = BY_TEST.getOrDefault(expected.get(0), List.of()).stream()
                        .anyMatch(v -> v.detector().contains("SharedStatefulCrypto")
                                && (v.message() + v.attributes())
                                        .contains(expected.get(1) + " is stateful"));
                if (!reported) {
                    missed.add(expected.get(0));
                }
            }
            assertTrue(missed.isEmpty(),
                    "Four threads shared one Mac, Signature or Cipher and nothing was reported "
                            + "for: " + missed + ". Check that CollectionAccessWeaver's "
                            + "SHARED_INSTANCE_ENTRIES lists the javax.crypto and Signature "
                            + "entries and that their hooks record to SharedStatefulCryptoDetector. "
                            + "Findings by test were: " + BY_TEST);
        } finally {
            findings.close();
        }
    }
}
