package se.deversity.asynctest.agent;

import com.example.agentfixture.ConfinedThroughFormatObjectBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.text.ParseException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The silent twin of {@link FormatObjectWeavingTest}: the same calls on formats built per call, on
 * a shared immutable {@code Format}, and on shared formats used under their own monitor (#1319).
 *
 * <p>An entry on {@code java.text.Format} matches every format, so this is the direction the
 * change could break: a finding here is the hook recording the static type it was woven for
 * rather than the receiver it was handed, or losing the monitor the caller holds.
 */
@Tag("e2e")
class FormatObjectWeavingSparesConfinedUseTest {

    private static AsyncFindings findings;

    private final ConfinedThroughFormatObjectBean confined = new ConfinedThroughFormatObjectBean();

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
    void confinedImmutableAndGuardedFormats() throws ParseException {
        confined.formatDecimal();
        confined.parseDecimalObject();
        confined.formatDateObject();
        confined.formatAndParseMessage();
        confined.formatMessageStatically();
        confined.formatThroughSharedImmutableFormat();
        confined.formatDecimalUnderItsMonitor();
        confined.formatMessageUnderItsMonitor();
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            for (String detector : List.of("SimpleDateFormat", "DecimalFormat")) {
                assertFalse(findings.violations().stream()
                                .anyMatch(v -> v.detector().contains(detector)),
                        "ConfinedThroughFormatObjectBean builds its formats per call, shares an "
                                + "immutable one, or holds the shared one's monitor, so there is "
                                + "nothing for " + detector + " to report. Findings were: "
                                + findings.violations());
            }
        } finally {
            findings.close();
        }
    }
}
