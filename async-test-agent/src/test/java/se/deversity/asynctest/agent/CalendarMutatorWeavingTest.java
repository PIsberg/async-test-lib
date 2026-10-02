package se.deversity.asynctest.agent;

import com.example.agentfixture.SharedCalendarMutatorBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a shared {@code Calendar} moved only by {@code add}, {@code roll}, {@code clear},
 * {@code setTime}, {@code setTimeInMillis} and {@code setTimeZone} is caught under the agent (#820).
 *
 * <p>Before #820 the agent wove {@code get} and the {@code set} overloads and nothing else, so this
 * subject, which never calls either, produced no record at all. The confined direction is
 * {@link StatefulJdkWeavingSparesConfinedUseTest}, whose per-call calendar now goes through the
 * same mutators.
 */
@Tag("e2e")
class CalendarMutatorWeavingTest {

    private static AsyncFindings findings;

    private final SharedCalendarMutatorBean shared = new SharedCalendarMutatorBean();

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
    void fourThreadsMoveOneCalendar() {
        shared.advance();
    }

    @AfterAll
    static void theSharedCalendarIsReported() {
        try {
            assertTrue(findings.violations().stream().anyMatch(v -> v.detector().contains("Calendar")),
                    "Four threads moved one Calendar through add, roll, clear, setTime, "
                            + "setTimeInMillis and setTimeZone, and nothing was reported. Check that "
                            + "CollectionAccessWeaver's shared-instance table lists those calls (#820). "
                            + "Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
