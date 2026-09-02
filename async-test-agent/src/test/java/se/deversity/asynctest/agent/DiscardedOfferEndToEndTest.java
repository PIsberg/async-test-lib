package se.deversity.asynctest.agent;

import com.example.agentfixture.CheckedOfferBean;
import com.example.agentfixture.DroppingOfferBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.report.Violation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins, with the agent attached, that a popped offer result is a finding and a checked one is not.
 *
 * <p>Both beans fill a bounded queue and both have offers rejected, so both runs carry the same
 * rejected count and a saturation line. What separates them is one instruction after the call:
 * {@code POP} in the dropping bean, {@code IFNE} in the checked one. The weaver reads that
 * difference and the dropped-element line must follow it exactly (#454).
 *
 * <p>The two beans use different capacities so each run's report can be told apart by its
 * saturation line. The checked bean's report has to be present for its silence to mean anything:
 * a run that reported nothing at all would pass a bare "no dropped line" assertion for free.
 */
@Tag("e2e")
class DiscardedOfferEndToEndTest {

    private static final String DROPPED = "discarded the result";

    private static AsyncFindings findings;

    private final DroppingOfferBean dropping = new DroppingOfferBean();

    private final CheckedOfferBean checked = new CheckedOfferBean();

    @BeforeAll
    static void attachWithConcurrencyWeaving() {
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
    void offeringAndNeverLooking() {
        dropping.enqueue("element");
    }

    @AsyncTest(threads = 4, invocations = 25)
    void offeringAndChecking() {
        checked.enqueue("element");
    }

    @AfterAll
    static void thePoppedResultIsTheFinding() {
        try {
            List<String> reports = findings.violations().stream()
                    .filter(v -> v.detector().contains("BlockingQueue"))
                    .map(DiscardedOfferEndToEndTest::text)
                    .toList();

            assertTrue(reports.stream().anyMatch(r -> r.contains("2/2") && r.contains(DROPPED)),
                    "Four threads offered to a full queue of two and popped every false, and no "
                            + "dropped-element line was reported. The call site is only rewritten "
                            + "if the BlockingQueue.offer entries carry whenResultDiscarded, the "
                            + "visitor replaces the POP with offerResultDiscarded, and the detector "
                            + "attributes it to the offer recorded just before. Reports: " + reports);
            assertTrue(reports.stream().anyMatch(r -> r.contains("3/3")),
                    "the checked bean's queue of three must have saturated and been reported, or "
                            + "its silence below is vacuous. Reports: " + reports);
            assertFalse(reports.stream().anyMatch(r -> r.contains("3/3") && r.contains(DROPPED)),
                    "The checked bean read every false, so its report must carry no "
                            + "dropped-element line. Its offers were rejected just like the "
                            + "dropping bean's; the only difference is IFNE against POP, and a "
                            + "dropped line here means the lookahead fired on a result that was "
                            + "read. Reports: " + reports);
        } finally {
            findings.close();
        }
    }

    /** The message and the report attribute together, whichever path the finding took. */
    private static String text(Violation v) {
        return v.message() + String.valueOf(v.attributes().get("report"));
    }
}
