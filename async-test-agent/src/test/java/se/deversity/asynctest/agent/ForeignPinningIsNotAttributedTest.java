package se.deversity.asynctest.agent;

import com.example.agentfixture.PinningThreadStartingBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A virtual thread that pins all through the run, started through the same woven
 * {@code Thread.Builder.start} but by a thread that is no run's worker, is not this run's finding
 * (#983).
 *
 * <p>The pinning feed attributes a non-worker thread only when a worker of this run started it.
 * Attributing every virtual thread pinning during the run would report another test's pins
 * whenever tests run in parallel.
 */
@Tag("e2e")
class ForeignPinningIsNotAttributedTest {

    private static AsyncFindings findings;
    private static final PinningThreadStartingBean BEAN = new PinningThreadStartingBean();
    private static final AtomicBoolean STOP = new AtomicBoolean();
    private static Thread foreign;

    @BeforeAll
    static void attachAndStartAForeignPinner() {
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
        foreign = BEAN.startPinningUntil(STOP);
    }

    @AsyncTest(threads = 2, invocations = 3, useVirtualThreads = true,
               includes = DetectorType.VIRTUAL_THREAD_PINNING)
    void blocksWithoutPinning() throws InterruptedException {
        Thread.sleep(30);
    }

    @AfterAll
    static void theForeignPinIsNotReported() throws InterruptedException {
        try {
            STOP.set(true);
            if (foreign != null) {
                foreign.join(5_000);
            }
            assertTrue(PinningThreadStartingBean.PINS.get() >= 3,
                    "the foreign thread pinned " + PinningThreadStartingBean.PINS.get()
                            + " times, so its silence says nothing");
            assertTrue(findings.violationsFrom("VirtualThreadPinning").isEmpty(),
                    "A thread started before the run, by no worker, pinned during it and was "
                            + "reported as this run's: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
