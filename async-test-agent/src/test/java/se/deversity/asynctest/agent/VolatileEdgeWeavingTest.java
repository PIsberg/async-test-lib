package se.deversity.asynctest.agent;

import com.example.agentfixture.ConstructorPublicationBean;
import com.example.agentfixture.ShadowedVolatileBean;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two volatile edges #813 left unmodelled, end to end: weaver, hooks, happens-before model and
 * {@code AtomicityValidator}.
 *
 * <p>Item 5: two volatile fields of one object that share a simple name have two clocks, and one
 * field reached through two static types has one. javac names a field instruction by its
 * qualifier's static type, so the weaver names a field by the class that declares it. Before it
 * did, the model matched fields by simple name, which made a subclass field stand in for the
 * superclass field it shadows and order a read that nothing published.
 *
 * <p>Item 4: a volatile write in a constructor releases. The weaver records no access for a
 * constructor's writes, so immutable objects do not read as mutated, and that used to drop the
 * release too, so safe publication through a constructor-set flag read as a race.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM and this needs {@code fields=true}.
 */
@Tag("e2e")
class VolatileEdgeWeavingTest {

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
    @DisplayName("a release of a subclass field does not order a read of the superclass field it shadows")
    void aShadowingFieldPublishesNothingToTheShadowedOne() throws InterruptedException {
        ShadowedVolatileBean.Sub bean = new ShadowedVolatileBean.Sub();
        int[] read = new int[1];
        List<String> findings = findings(bean::publishThroughOwnReady, () -> read[0] = bean.bumpAfterBaseReady());

        assertEquals(2, read[0], "the reader updated the payload after the write, or nothing was measured");
        assertTrue(findings.stream().anyMatch(f -> f.contains("payload")),
                "The writer released Sub.ready; the reader read ShadowedVolatileBean.ready, another "
                        + "field that nothing released, so its update of payload is not ordered after "
                        + "the write. Findings were: " + findings);
    }

    @Test
    @DisplayName("one field written through the subclass and read through the superclass orders the read")
    void anInheritedFieldIsOneFieldWhicheverTypeNamesIt() throws InterruptedException {
        ShadowedVolatileBean.Sub bean = new ShadowedVolatileBean.Sub();
        int[] read = new int[1];
        List<String> findings = findings(bean::publishThroughInherited, () -> read[0] = bean.bumpAfterInherited());

        assertEquals(2, read[0], "the reader saw the published flag and updated the payload, or nothing was measured");
        assertFalse(findings.stream().anyMatch(f -> f.contains("payload")),
                "inherited is declared once; the write names it through Sub, the read through "
                        + "ShadowedVolatileBean, and the read saw the value written, so the payload "
                        + "update is ordered after the write. Findings were: " + findings);
    }

    @Test
    @DisplayName("a volatile write in a constructor publishes what the thread wrote before it (#813 item 4)")
    void aConstructorsVolatileWritePublishes() throws InterruptedException {
        ConstructorPublicationBean bean = new ConstructorPublicationBean();
        int[] read = new int[1];
        List<String> findings = findings(bean::updateThenConstruct, () -> read[0] = bean.bumpAfterReady());

        assertEquals(2, read[0], "the reader saw the flag and updated the data, or nothing was measured");
        assertFalse(findings.stream().anyMatch(f -> f.contains(".data")),
                "the writer updated data and then constructed a Flagged whose constructor set the "
                        + "volatile ready; the reader read ready == true first, so its update of data "
                        + "is ordered after the writer's. Findings were: " + findings);
    }

    @Test
    @DisplayName("the same update with no read of the flag keeps its finding")
    void anUpdateThatReadNoFlagIsStillARace() throws InterruptedException {
        ConstructorPublicationBean bean = new ConstructorPublicationBean();
        int[] read = new int[1];
        List<String> findings = findings(bean::updateThenConstruct, () -> read[0] = bean.bumpWithoutReady());

        assertEquals(2, read[0], "the reader updated the data after the writer, or nothing was measured");
        assertTrue(findings.stream().anyMatch(f -> f.contains(".data")),
                "no volatile read orders this update after the writer's. Findings were: " + findings);
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
            }, "shadow-writer").start();
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
            }, "shadow-reader").start();
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }
}
