package se.deversity.asynctest.agent;

import java.util.Date;

import com.example.agentfixture.ConfinedFormatterBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The negative half of {@link SharedInstanceWeavingTest}, and the one that gates the table.
 *
 * <p>{@link ConfinedFormatterBean} constructs a formatter per call, which is the fix most codebases
 * actually apply, and the agent substitutes exactly the same {@code format} call site there. So the
 * difference the detector has to see is not which instruction ran but how many threads touched one
 * instance. A finding here would mean the substitution reports the call rather than the sharing,
 * putting a false positive on every correctly written formatter in a woven codebase - which is a
 * far larger population than the buggy one.
 */
@Tag("e2e")
class SharedInstanceWeavingSparesConfinedUseTest {

    private static AsyncFindings findings;

    private final ConfinedFormatterBean confined = new ConfinedFormatterBean();

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
    void aFormatterPerCall() {
        confined.render(new Date());
    }

    @AfterAll
    static void confinedUseIsNotReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("SimpleDateFormat")),
                    "ConfinedFormatterBean constructs a SimpleDateFormat per call, so no instance "
                            + "is ever touched by two threads and there is nothing to report. A "
                            + "finding here is a false positive on the standard fix for this bug, "
                            + "which would be worse than not detecting it at all. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
