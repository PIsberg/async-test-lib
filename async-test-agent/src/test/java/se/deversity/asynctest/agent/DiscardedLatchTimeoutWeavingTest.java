package se.deversity.asynctest.agent;

import com.example.agentfixture.StopLatchBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #1501, the other direction: {@code done.await(1, MILLISECONDS);} as a statement. The boolean
 * that says the latch never reached zero is popped, so the timeout must still be reported once
 * the weaver tells a read result from a discarded one.
 *
 * <p>A class of its own because {@code selfAttach} is at most once per JVM and each test class gets
 * a fork of its own.
 */
@Tag("e2e")
class DiscardedLatchTimeoutWeavingTest {

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
    void aDiscardedTimeoutIsTheFinding() throws InterruptedException {
        bean.awaitIgnoringTheTimeout();
    }

    @AfterAll
    static void verdict() {
        try {
            assertTrue(findings.violations().stream().anyMatch(v -> v.detector().contains("Latch")),
                    "the discarded timeout is the finding; findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
