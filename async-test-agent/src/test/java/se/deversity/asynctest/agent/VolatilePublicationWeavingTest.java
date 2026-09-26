package se.deversity.asynctest.agent;

import com.example.agentfixture.VolatilePublicationBean;
import com.example.agentfixture.VolatileShapesBean;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a woven volatile read orders, judged end to end: weaver, hooks, happens-before model and
 * {@code AtomicityValidator}.
 *
 * <p>Each case runs a writer and a reader on two threads that the test coordinates with latches
 * the agent does not see, because the test class is not woven. The only edge the model can know
 * about is the one the fixture's volatile field makes, so a case asserts exactly that edge: a
 * correct publication stays silent, and the same accesses without a volatile read that saw the
 * published value keep their finding.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM and this one needs
 * {@code fields=true}; {@code forkEvery=1} gives it its own JVM.
 */
@Tag("e2e")
class VolatilePublicationWeavingTest {

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

    /**
     * Runs {@code writer} on one worker and {@code reader} on another, and returns the validator's
     * findings.
     *
     * <p>The writer waits until the reader runs the {@link Runnable} it is handed, which returns
     * once the writer has finished, so the reader decides which of its accesses come before the
     * writer's and which after. A reader that never runs it lets the writer go when it returns.
     */
    private static List<String> findings(Runnable writer, Consumer<Runnable> reader)
            throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    await(go);
                    writer.run();
                } finally {
                    written.countDown();
                    done.countDown();
                }
            }, "volatile-writer").start();
            new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    reader.accept(() -> {
                        go.countDown();
                        await(written);
                    });
                } finally {
                    go.countDown();
                    done.countDown();
                }
            }, "volatile-reader").start();
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the other worker never got there");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean mentions(List<String> findings, String field) {
        return findings.stream().anyMatch(f -> f.contains(field));
    }

    @Test
    @DisplayName("a node published through a volatile next is ordered for the reader that read it (#804)")
    void linkedNodePublicationIsSilent() throws InterruptedException {
        VolatilePublicationBean bean = new VolatilePublicationBean();
        boolean[] updated = new boolean[1];
        List<String> findings = findings(() -> bean.link(1), writerRuns -> {
            writerRuns.run();
            updated[0] = bean.updateLinked();
        });

        assertTrue(updated[0], "the reader must have reached the node, or nothing was measured");
        assertFalse(mentions(findings, "Node.value"),
                "The writer set node.value and then published the node with the volatile write "
                        + "head.next = node; the reader's volatile read of head.next returned that "
                        + "node, so the writer's write happens before the reader's read and write "
                        + "of node.value. A finding here means the acquire of head.next did not "
                        + "order the reader's later access to the object it returned. Findings "
                        + "were: " + findings);
    }

    @Test
    @DisplayName("the same node reached through a plain field keeps its finding (#804)")
    void plainLinkedNodeIsReported() throws InterruptedException {
        VolatilePublicationBean bean = new VolatilePublicationBean();
        boolean[] updated = new boolean[1];
        List<String> findings = findings(() -> bean.linkPlain(1), writerRuns -> {
            writerRuns.run();
            updated[0] = bean.updatePlain();
        });

        assertTrue(updated[0], "the reader must have reached the node, or nothing was measured");
        assertTrue(mentions(findings, "Node.value"),
                "The node was stored through a plain field, so nothing orders the writer's "
                        + "node.value write before the reader's update: the twin that shows the "
                        + "silence above comes from the volatile read. Findings were: " + findings);
    }

    @Test
    @DisplayName("a volatile read that saw the published value orders the update after it")
    void flagPublicationIsSilent() throws InterruptedException {
        VolatilePublicationBean bean = new VolatilePublicationBean();
        boolean[] seen = new boolean[1];
        List<String> findings = findings(() -> bean.publish(5), writerRuns -> {
            writerRuns.run();
            seen[0] = bean.bumpDataAfterReady(() -> { });
        });

        assertTrue(seen[0], "the reader read ready after the writer finished");
        assertEquals(6, bean.observedData(), "the reader updated what the writer wrote");
        assertFalse(mentions(findings, ".data"),
                "The reader's volatile read of ready returned true, the value the writer's "
                        + "volatile write stored after writing data, so data is published. "
                        + "Findings were: " + findings);
    }

    @Test
    @DisplayName("a volatile read that returned the older value orders nothing after it (#742)")
    void aReadOfTheOlderValueOrdersNothing() throws InterruptedException {
        VolatilePublicationBean bean = new VolatilePublicationBean();
        boolean[] seen = {true};
        // One call spans the writer: its volatile read of ready returns false, the writer then
        // writes data and ready, and only then does the same call update data.
        List<String> findings = findings(() -> bean.publish(5),
                writerRuns -> seen[0] = bean.bumpDataAfterReady(writerRuns));

        assertFalse(seen[0], "the volatile read came before the writer ran");
        assertEquals(6, bean.observedData(), "the update came after the writer finished");
        assertTrue(mentions(findings, ".data"),
                "The reader's volatile read of ready returned false, the value from before the "
                        + "writer's volatile write, so it synchronizes with nothing the writer "
                        + "did and the later update of data races with the writer's write. "
                        + "Silence means the acquire took the writer's release although the read "
                        + "never saw the value it published. Findings were: " + findings);
    }

    @Test
    @DisplayName("every volatile stack shape survives the verifier and keeps its value")
    void everyVolatileShapeKeepsItsValue() {
        assertEquals("true,3,x,300,70001,1099511627777,1.5,4.5,n,4,-8589934593,-2.0,-7,true",
                new VolatileShapesBean().roundTrip(),
                "Each volatile store and load is woven with a copy of its value handed to a hook, "
                        + "built differently for one slot and two, instance and static. A "
                        + "VerifyError or a wrong value here means one of those copies left the "
                        + "operand stack in a different shape than it found it.");
    }

    @Test
    @DisplayName("a two-slot volatile publishes like a one-slot one, and its older value orders nothing")
    void aWideVolatileIsMatchedByValue() throws InterruptedException {
        VolatilePublicationBean published = new VolatilePublicationBean();
        long[] seen = new long[2];
        List<String> silent = findings(() -> published.publishWide(5), writerRuns -> {
            writerRuns.run();
            seen[0] = published.bumpDataAfterWide(() -> { });
        });
        VolatilePublicationBean raced = new VolatilePublicationBean();
        List<String> reported = findings(() -> raced.publishWide(5),
                writerRuns -> seen[1] = raced.bumpDataAfterWide(writerRuns));

        assertEquals(7L, seen[0], "the first reader read the long the writer stored");
        assertFalse(mentions(silent, ".data"),
                "The long the reader read is the one the writer stored after writing data. "
                        + "Findings were: " + silent);
        assertEquals(0L, seen[1], "the second reader read the long before the writer stored it");
        assertTrue(mentions(reported, ".data"),
                "The second reader read 0, not the 7 the writer stored, so nothing orders its "
                        + "update of data. Findings were: " + reported);
    }

    @Test
    @DisplayName("a static volatile publishes through its declaring class")
    void aStaticVolatilePublishes() throws InterruptedException {
        VolatilePublicationBean bean = new VolatilePublicationBean();
        boolean[] seen = new boolean[1];
        List<String> findings = findings(() -> bean.publishStatic(5), writerRuns -> {
            writerRuns.run();
            seen[0] = bean.bumpDataAfterStaticReady();
        });

        assertTrue(seen[0], "the reader read the flag after the writer set it");
        assertFalse(mentions(findings, ".data"),
                "The static volatile's hooks name the declaring class as the owner on both sides, "
                        + "so the read of the value the writer stored orders data. Findings were: "
                        + findings);
    }

    @Test
    @DisplayName("a spin-wait on a volatile orders what follows it")
    void spinWaitIsSilent() throws InterruptedException {
        VolatilePublicationBean bean = new VolatilePublicationBean();
        // The writer is let go from a helper thread, so it runs while the reader spins.
        List<String> findings = findings(() -> bean.publish(5), writerRuns -> {
            new Thread(writerRuns, "writer-release").start();
            bean.bumpDataAfterSpinningOnReady();
        });

        assertEquals(6, bean.observedData(), "the reader updated what the writer wrote");
        assertFalse(mentions(findings, ".data"),
                "Every read of ready before the write returned false and acquired nothing; the "
                        + "one that returned true acquired the write. Findings were: " + findings);
    }

    @Test
    @DisplayName("double-checked locking with a volatile field orders the unlocked read")
    void doubleCheckedLockingIsSilent() throws InterruptedException {
        VolatilePublicationBean bean = new VolatilePublicationBean();
        int[] values = new int[2];
        List<String> findings = findings(() -> values[0] = bean.lazyValue(), writerRuns -> {
            writerRuns.run();
            values[1] = bean.lazyValue();
        });

        assertEquals(42, values[0]);
        assertEquals(42, values[1]);
        assertFalse(mentions(findings, "Lazy.value"),
                "The builder set value under the lock and published the object through the "
                        + "volatile field; the other thread read value without the lock, after the "
                        + "volatile read that returned that object. Findings were: " + findings);
    }
}
