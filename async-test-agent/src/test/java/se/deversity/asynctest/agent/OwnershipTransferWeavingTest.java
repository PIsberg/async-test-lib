package se.deversity.asynctest.agent;

import com.example.agentfixture.HandOffChunkBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a take the weaver can see ends a race that is not a race (#555), and only a take.
 *
 * <p>Each case passes one chunk between two threads, unlocked, through the full woven pipeline:
 * out of a queue, out of an {@code AtomicReference}, out of a {@code VarHandle} slot. The twin
 * does the same reads and writes on a chunk nothing takes. If the take events stopped arriving,
 * the three quiet cases would fire like the twin; if takes excused everything, the twin would go
 * quiet with them.
 *
 * <p>Separate class because {@code selfAttach} is at-most-once per JVM and this class needs
 * {@code fields=true,collections=true}; {@code reuseForks=false} gives it its own fork.
 */
@Tag("e2e")
class OwnershipTransferWeavingTest {

    @BeforeAll
    static void attachWithFieldAndCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** Runs {@code work} 200 times on each of two threads with the bridge active, then analyzes. */
    private static AtomicityValidator.AtomicityReport drive(Runnable work) throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(2);
        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            for (int t = 0; t < 2; t++) {
                new Thread(() -> {
                    workerThreadIds.add(Thread.currentThread().threadId());
                    for (int i = 0; i < 200; i++) {
                        work.run();
                    }
                    done.countDown();
                }, "hand-off-worker-" + t).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity();
    }

    @Test
    @DisplayName("a chunk polled from a queue, used unlocked and offered back is not a race")
    void pollingFromAQueueTakesOwnership() throws Exception {
        HandOffChunkBean bean = new HandOffChunkBean();
        AtomicityValidator.AtomicityReport report = drive(bean::useThroughQueue);

        assertFalse(report.hasIssues(),
                "only the thread that polled the chunk touches it before offering it back; the "
                        + "woven Queue.poll must report the take. Findings: "
                        + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("a chunk swapped out of an AtomicReference is exclusive to the thread that swapped it")
    void atomicReferenceGetAndSetTakesOwnership() throws Exception {
        HandOffChunkBean bean = new HandOffChunkBean();
        AtomicityValidator.AtomicityReport report = drive(bean::useThroughAtomicReference);

        assertFalse(report.hasIssues(),
                "getAndSet(null) removed the chunk from the slot before it was used; the weaver "
                        + "must report the returned reference as taken. Findings: "
                        + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("a chunk swapped out of a VarHandle slot is exclusive to the thread that swapped it")
    void varHandleGetAndSetTakesOwnership() throws Exception {
        HandOffChunkBean bean = new HandOffChunkBean();
        AtomicityValidator.AtomicityReport report = drive(bean::useThroughVarHandle);

        assertFalse(report.hasIssues(),
                "a signature-polymorphic VarHandle.getAndSet returns the reference it replaced; "
                        + "that is the take. Findings: "
                        + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("the same reads and writes on a chunk nothing takes still fire")
    void usingAChunkNobodyTookIsStillReported() throws Exception {
        HandOffChunkBean bean = new HandOffChunkBean();
        AtomicityValidator.AtomicityReport report = drive(bean::useWithoutTaking);

        assertTrue(report.hasIssues(),
                "two threads increment one chunk with no lock and no take between them; that is "
                        + "the race, and the ownership model must not have quietened it");
    }
}
