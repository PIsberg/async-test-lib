package se.deversity.asynctest.agent;

import com.example.agentfixture.InterruptHandlingShapes;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.report.Violation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A swallowing {@code InterruptedException} handler in unmodified code, found through the real
 * weaver while no interrupt ever arrives.
 *
 * <p>The body calls one swallowing shape and one restoring shape of the fixture. The swallowing
 * handler's location must reach both interrupt detectors; the restoring one must reach neither.
 */
@Tag("e2e")
class InterruptFeedWeavingTest {

    private static AsyncFindings findings;
    private static final InterruptHandlingShapes SHAPES = new InterruptHandlingShapes();

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

    @AsyncTest(threads = 2, invocations = 3,
            includes = {DetectorType.INTERRUPT_SWALLOWING, DetectorType.INTERRUPT_MISHANDLING})
    void callsOneSwallowingAndOneRestoringShape() {
        SHAPES.swallowLogged();
        SHAPES.restoreTheFlag();
    }

    private static boolean named(String detector, String method) {
        List<Violation> found = findings.violationsFrom(detector);
        String needle = InterruptHandlingShapes.class.getName() + "." + method + ":";
        return found.stream().anyMatch(v -> v.message().contains(needle)
                || String.valueOf(v.attributes().get("report")).contains(needle));
    }

    @AfterAll
    static void onlyTheSwallowingHandlerIsReported() {
        try {
            assertTrue(named("InterruptSwallowing", "swallowLogged"),
                    "swallowLogged catches InterruptedException, logs and goes on. With "
                            + "collections=true its method start names the handler, and nothing "
                            + "reached INTERRUPT_SWALLOWING: check InterruptHandlerWeaver is in "
                            + "AsyncTestAgent.collectionSubstitutions. Findings were: "
                            + findings.violations());
            assertTrue(named("InterruptMonitor", "swallowLogged"),
                    "INTERRUPT_MISHANDLING, which the default preset runs, must hear it too. "
                            + "Findings were: " + findings.violations());
            assertFalse(named("InterruptSwallowing", "restoreTheFlag")
                            || named("InterruptMonitor", "restoreTheFlag"),
                    "restoreTheFlag calls Thread.currentThread().interrupt(); naming it reports "
                            + "the fix itself. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
