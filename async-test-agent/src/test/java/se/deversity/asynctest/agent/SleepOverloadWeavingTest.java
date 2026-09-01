package se.deversity.asynctest.agent;

import com.example.agentfixture.SleepingDurationInSynchronizedMethodBean;
import com.example.agentfixture.SleepingNanosInSynchronizedMethodBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.report.Violation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that the other two {@code Thread.sleep} overloads are substituted too (#440).
 *
 * <p>{@code SleepInSynchronizedMethodWeavingTest} covers {@code sleep(long)}. These two were
 * tracked gaps rather than decisions: the static entry carries a {@code whenSynchronized} hook, so
 * an overload needs its own monitor-taking variant on the hooks class rather than a copy of the
 * plain pattern, and that is what kept them out of #434's sweep.
 *
 * <p>Two fixture beans rather than two methods on one, because the finding names its monitor as
 * {@code getClass().getName() + "@" + identityHashCode}. That is what makes each overload
 * separately assertable here: with one bean, either overload weaving would satisfy both
 * assertions, which is the shape of green that says nothing.
 */
@Tag("e2e")
class SleepOverloadWeavingTest {

    private static AsyncFindings findings;

    private final SleepingDurationInSynchronizedMethodBean duration =
            new SleepingDurationInSynchronizedMethodBean();

    private final SleepingNanosInSynchronizedMethodBean nanos =
            new SleepingNanosInSynchronizedMethodBean();

    @BeforeAll
    static void attachWithStaticWeaving() {
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

    @AsyncTest(threads = 4, invocations = 10)
    void sleepingADurationInsideASynchronizedMethod() throws InterruptedException {
        duration.process();
    }

    @AsyncTest(threads = 4, invocations = 10)
    void sleepingSubMillisecondInsideASynchronizedMethod() throws InterruptedException {
        nanos.process();
    }

    @AfterAll
    static void bothOverloadsAreReportedAgainstTheirOwnMonitor() {
        try {
            List<Violation> sleepFindings = findings.violations().stream()
                    .filter(v -> v.detector().contains("SleepInLock"))
                    .toList();

            assertTrue(namesMonitorOfType(sleepFindings, "SleepingDurationInSynchronizedMethodBean"),
                    "Thread.sleep(Duration) inside a synchronized method reported nothing. The "
                            + "call site is only woven if STATIC_ENTRIES carries an entry for the "
                            + "Duration overload with .whenSynchronized(...), and only resolves "
                            + "if AgentSleepHooks has sleepHoldingMonitor(Duration, Object). "
                            + "Findings were: " + sleepFindings);
            assertTrue(namesMonitorOfType(sleepFindings, "SleepingNanosInSynchronizedMethodBean"),
                    "Thread.sleep(0, 500_000) inside a synchronized method reported nothing. "
                            + "Either the long/int overload is unwoven, or the hook truncated the "
                            + "nanosecond part to zero, which recordSleep drops. Findings were: "
                            + sleepFindings);
        } finally {
            findings.close();
        }
    }

    /**
     * {@return whether any finding names a monitor of {@code simpleName}}
     *
     * <p>The monitor is printed as its class name and identity hash, so the simple name is enough
     * to tell the two beans apart without pinning a hash that changes every run.
     *
     * @param sleepFindings the SleepInLock findings of the run
     * @param simpleName    the fixture bean whose instance is the monitor
     */
    private static boolean namesMonitorOfType(List<Violation> sleepFindings, String simpleName) {
        // The message is the report's first line only; the events, and with them the monitor each
        // one names, travel in the "report" attribute. Searching the message alone would fail
        // against a working weave, which is how this helper was first written.
        return sleepFindings.stream().anyMatch(v ->
                v.message().contains(simpleName)
                        || String.valueOf(v.attributes().get("report")).contains(simpleName));
    }
}
