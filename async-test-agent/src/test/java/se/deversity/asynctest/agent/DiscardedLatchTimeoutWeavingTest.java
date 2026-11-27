package se.deversity.asynctest.agent;

import com.example.agentfixture.StopLatchBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #1501, the other direction: a timed await whose result nobody branches on, popped as a
 * statement or returned to a caller (Guava's {@code awaitUninterruptibly} shape) that then drops
 * it. The timeout must still be reported once the weaver tells a tested result from the rest.
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

    @AsyncTest(threads = 1, invocations = 5, detectAll = true)
    void workSkippedOnATimeoutIsTheFinding() throws InterruptedException {
        bean.workOnlyIfReached();
    }

    @AsyncTest(threads = 1, invocations = 5, detectAll = true)
    void aReturnedTimeoutIsNotTestedHere() throws InterruptedException {
        bean.awaitReturningTheTimeout();
    }

    @AfterAll
    static void verdict() {
        try {
            // One finding per body: a shape the weaver wrongly took for handled would leave two.
            long latchFindings = findings.violations().stream()
                    .filter(v -> v.detector().equals("CountDownLatchDetector")).count();
            assertEquals(3, latchFindings,
                    "the popped, the IFEQ-skipped and the returned timeout each report; findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
