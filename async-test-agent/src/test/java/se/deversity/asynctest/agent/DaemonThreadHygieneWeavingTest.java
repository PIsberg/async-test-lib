package se.deversity.asynctest.agent;

import com.example.agentfixture.BareThreadStartingBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that an un-setDaemon'd thread started through a woven call site is caught.
 */
@Tag("e2e")
class DaemonThreadHygieneWeavingTest {

    private static AsyncFindings findings;
    private static final CountDownLatch RELEASE = new CountDownLatch(1);
    private static final List<Thread> STARTED = new CopyOnWriteArrayList<>();

    private final BareThreadStartingBean starter = new BareThreadStartingBean();

    @BeforeAll
    static void attachWithThreadWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 2, invocations = 1, detectDaemonThreadHygiene = true)
    void startingBareThreadInsideRun() {
        Thread t = starter.startBare(() -> {
            try {
                RELEASE.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        STARTED.add(t);
    }

    @AfterAll
    static void bareThreadIsReported() {
        RELEASE.countDown();
        for (Thread t : STARTED) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("DaemonThreadHygiene")),
                    "BareThreadStartingBean started a bare thread without setDaemon(true) and "
                            + "nothing was reported. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
