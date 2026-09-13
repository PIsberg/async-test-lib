package se.deversity.asynctest.agent;

import com.example.agentfixture.SharedThroughWiderTypeBean;
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
 * Pins that a shared unsafe JDK object is caught when the call site holds it as a wider type.
 *
 * <p>The weaver matched a call only when its owner was the concrete type or a subtype of it, so
 * {@code dateFormat.format(date)} on a field typed {@code DateFormat}, {@code appendable.append(s)}
 * on a {@code StringBuilder} passed as {@code Appendable}, and {@code numberFormat.parse(s)} were
 * never substituted (#542). That is how Jackson, Guava and Spring hold these objects, so real
 * library code was out of reach. The negative direction, including shared objects of the wider
 * type that are not the unsafe one, is {@link WiderOwnerWeavingSparesConfinedUseTest}.
 */
@Tag("e2e")
class WiderOwnerWeavingTest {

    private static AsyncFindings findings;

    private final SharedThroughWiderTypeBean shared = new SharedThroughWiderTypeBean();

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
    void fourThreadsThroughTheWiderTypes() {
        shared.formatDate();
        shared.parseNumber();
        shared.appendText();
    }

    @AfterAll
    static void eachSharedInstanceIsReported() {
        try {
            for (String detector : List.of("SimpleDateFormat", "DecimalFormat", "StringBuilder")) {
                assertTrue(findings.violations().stream()
                                .anyMatch(v -> v.detector().contains(detector)),
                        "Four threads shared one " + detector + " reached through a wider static "
                                + "type (DateFormat, NumberFormat.parse, Appendable) and nothing "
                                + "was reported. Check that CollectionAccessWeaver's "
                                + "SHARED_INSTANCE_ENTRIES lists the wider owner and that the hook "
                                + "records when the runtime receiver is the unsafe type. Findings "
                                + "were: " + findings.violations());
            }
        } finally {
            findings.close();
        }
    }
}
