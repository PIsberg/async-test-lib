package se.deversity.asynctest.agent;

import com.example.agentfixture.LateDyingChild;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.Preset;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A thread the body started through woven code and did not join, which fails an assertion just
 * after the run's last check, still fails the run (#1379): the runner gives the run's started
 * threads a short, bounded join before that check.
 *
 * <p>Its own class because {@code selfAttach} is once per JVM and {@code forkEvery=1} gives each
 * test class its own.
 */
@Tag("e2e")
class LateBodyThreadDeathWeavingTest {

    @BeforeAll
    static void attach() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        // collections=true substitutes Thread.start, which is how the run learns of the child; it is
        // on in the default attach.
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
    }

    @AfterAll
    static void stopRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    void aStartedThreadThatFailsJustAfterTheLastRoundFailsTheRun() {
        AssertionError failure = assertThrows(AssertionError.class, () -> AsyncTestRunner.run(
                AsyncTestConfig.builder().threads(1).invocations(1).preset(Preset.NONE)
                        .licenseMockMode(true).build(),
                LateDyingChild::startUnjoined));

        assertTrue(String.valueOf(failure.getMessage()).contains(LateDyingChild.MESSAGE),
                "the run must fail with the child's own assertion; was: " + failure.getMessage());
    }
}
