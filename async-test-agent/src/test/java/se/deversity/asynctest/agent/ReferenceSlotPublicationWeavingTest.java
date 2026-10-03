package se.deversity.asynctest.agent;

import com.example.agentfixture.ReferenceSlotPublicationBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #741's last item, end to end: an acquiring read of a reference slot that is not an
 * {@code AtomicReference} acquires what the store whose value it returned published. The stores
 * were already woven, as ownership offers; the reads were not, and the stores released nothing, so
 * a plain update published through an updater, an {@code AtomicReferenceArray} or a
 * {@code VarHandle} read as a race on the reader's side.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM and this needs {@code fields=true}.
 */
@Tag("e2e")
class ReferenceSlotPublicationWeavingTest {

    @BeforeAll
    static void attachWithFieldWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    @DisplayName("an AtomicReferenceFieldUpdater get acquires the set whose value it returned")
    void anUpdaterGetIsAnAcquire() throws InterruptedException {
        ReferenceSlotPublicationBean bean = new ReferenceSlotPublicationBean();
        assertOrdered(bean, bean::publishThroughUpdater, bean::bumpAfterUpdater, "updater");
    }

    @Test
    @DisplayName("an AtomicReferenceArray get acquires the set of that element whose value it returned")
    void anArrayGetIsAnAcquire() throws InterruptedException {
        ReferenceSlotPublicationBean bean = new ReferenceSlotPublicationBean();
        assertOrdered(bean, bean::publishThroughArray, bean::bumpAfterArray, "AtomicReferenceArray");
    }

    @Test
    @DisplayName("a VarHandle getAcquire of an instance field acquires the setRelease it saw")
    void aFieldHandleGetAcquireIsAnAcquire() throws InterruptedException {
        ReferenceSlotPublicationBean bean = new ReferenceSlotPublicationBean();
        assertOrdered(bean, bean::publishThroughHandle, bean::bumpAfterHandle, "field VarHandle");
    }

    @Test
    @DisplayName("a VarHandle getVolatile of an array element acquires the setVolatile it saw")
    void anElementHandleGetVolatileIsAnAcquire() throws InterruptedException {
        ReferenceSlotPublicationBean bean = new ReferenceSlotPublicationBean();
        assertOrdered(bean, bean::publishThroughElementHandle, bean::bumpAfterElementHandle,
                "array-element VarHandle");
    }

    @Test
    @DisplayName("the same update with no read of the slot keeps its finding")
    void anUpdateThatReadNoSlotIsStillARace() throws InterruptedException {
        ReferenceSlotPublicationBean bean = new ReferenceSlotPublicationBean();
        int[] result = new int[1];
        List<String> findings = findings(bean::publishThroughUpdater, () -> result[0] = bean.bumpWithoutReading());

        assertEquals(2, result[0], "the reader updated the data after the writer, or nothing was measured");
        assertTrue(findings.stream().anyMatch(f -> f.contains(".data")),
                "no acquiring read orders this update after the writer's. Findings were: " + findings);
    }

    private static void assertOrdered(ReferenceSlotPublicationBean bean, Runnable writer,
                                      IntSupplier reader, String through) throws InterruptedException {
        int[] result = new int[1];
        List<String> findings = findings(writer, () -> result[0] = reader.getAsInt());

        assertEquals(2, result[0], "the reader saw the token and updated the data, or nothing was measured");
        assertFalse(findings.stream().anyMatch(f -> f.contains(".data")),
                "the writer updated data and stored a token through the " + through + "; the reader's "
                        + "acquiring read returned that token, so its update of data is ordered after "
                        + "the writer's. Findings were: " + findings);
    }

    /** Runs the writer to completion, then the reader, on two workers the model does not order. */
    private static List<String> findings(Runnable writer, Runnable reader) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    writer.run();
                } finally {
                    written.countDown();
                    done.countDown();
                }
            }, "slot-writer").start();
            new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    assertTrue(written.await(10, TimeUnit.SECONDS), "the writer never finished");
                    reader.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "slot-reader").start();
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }
}
