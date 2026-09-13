package se.deversity.asynctest.agent;

import com.example.agentfixture.ConfinedThroughWiderTypeBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.io.IOException;
import java.text.ParseException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The negative half of {@link WiderOwnerWeavingTest}.
 *
 * <p>Two things must stay silent. The confined objects, reached through the same wider-typed call
 * sites, are the ordinary fix. And the two shared objects of the wider type that are <em>not</em>
 * the unsafe one - a stateless {@code DateFormat} subclass and a {@code StringBuffer} - are what
 * widening the match brings in for the first time. A finding on either would mean the hook
 * recorded the static type rather than checking what it was actually handed.
 */
@Tag("e2e")
class WiderOwnerWeavingSparesConfinedUseTest {

    private static AsyncFindings findings;

    private final ConfinedThroughWiderTypeBean confined = new ConfinedThroughWiderTypeBean();

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
    void confinedAndSafeReceiversThroughTheWiderTypes() throws ParseException, IOException {
        confined.formatDate();
        confined.parseNumber();
        confined.appendText();
        confined.formatThroughSharedNonSimpleFormat();
        confined.appendToSharedStringBuffer();
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            for (String detector : List.of("SimpleDateFormat", "DecimalFormat", "StringBuilder")) {
                assertFalse(findings.violations().stream()
                                .anyMatch(v -> v.detector().contains(detector)),
                        "ConfinedThroughWiderTypeBean either builds its " + detector + " per call "
                                + "or shares an object of the wider type that is not one, so there "
                                + "is nothing to report. A finding is the wider entry recording "
                                + "its static type instead of the receiver's runtime type. "
                                + "Findings were: " + findings.violations());
            }
        } finally {
            findings.close();
        }
    }
}
