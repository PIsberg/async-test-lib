package se.deversity.asynctest.agent;

import com.example.agentfixture.StopLatchBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #1501: a stop-latch poll, {@code while (!stop.await(1, MILLISECONDS) && ...)}. The caller reads
 * every timeout, which is how a stop latch is meant to be used, yet each one was reported as a
 * CRITICAL latch timeout and a missing countDown.
 *
 * <p>A class of its own because {@code selfAttach} is at most once per JVM and each test class gets
 * a fork of its own.
 */
@Tag("e2e")
class HandledLatchTimeoutWeavingTest {

    private static AsyncFindings findings;

    private final StopLatchBean bean = new StopLatchBean();

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

    @AsyncTest(threads = 1, invocations = 5, detectAll = true)
    void aStopLatchPollIsNotATimeout() throws InterruptedException {
        bean.pollUntilStopped();
    }

    @AfterAll
    static void verdict() {
        try {
            assertFalse(findings.violations().stream().anyMatch(v -> v.detector().contains("Latch")),
                    "every timeout was read by the loop condition; findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
