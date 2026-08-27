package se.deversity.asynctest.agent;

import java.util.List;

import com.example.agentfixture.ConfinedStatefulJdkBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The negative half of {@link StatefulJdkWeavingTest}, and the one that gates the second tranche.
 *
 * <p>{@link ConfinedStatefulJdkBean} builds each object inside the method that uses it, which is
 * the fix most codebases apply, and the agent substitutes exactly the same call sites there. So
 * what the detectors must distinguish is not which instruction ran but how many threads touched one
 * instance. A finding here would put a false positive on every correctly written use in a woven
 * codebase - a far larger population than the buggy one, and the reason a tool gets switched off.
 *
 * <p>{@code StringBuilder} is the one to watch: it is used constantly and correctly. String
 * concatenation itself does not reach the substitution, because javac has compiled that to
 * {@code invokedynamic} since JDK 9, so only an explicit builder is woven - but an explicit
 * confined builder is still the common case, and it is what this asserts about.
 */
@Tag("e2e")
class StatefulJdkWeavingSparesConfinedUseTest {

    private static AsyncFindings findings;

    private final ConfinedStatefulJdkBean confined = new ConfinedStatefulJdkBean();

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
    void oneOfEachPerCall() {
        confined.year();
        confined.append();
        confined.money();
    }

    @AfterAll
    static void confinedUseIsNotReported() {
        try {
            for (String detector : List.of("Calendar", "StringBuilder", "DecimalFormat")) {
                assertFalse(findings.violations().stream()
                                .anyMatch(v -> v.detector().contains(detector)),
                        "ConfinedStatefulJdkBean builds its " + detector + " inside the method "
                                + "that uses it, so no instance is ever touched by two threads and "
                                + "there is nothing to report. A finding here is a false positive "
                                + "on the standard fix for this bug, which would be worse than "
                                + "not detecting it at all. Findings were: "
                                + findings.violations());
            }
        } finally {
            findings.close();
        }
    }
}
