package se.deversity.asynctest.agent;

import com.example.agentfixture.AbaSlotHeads;
import com.example.agentfixture.AbaStackBean;
import com.example.agentfixture.AbaStackBean.Node;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.ABAProblemDetector;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code ABAProblemDetector} fed by the agent through the reference slots that are not an
 * {@code AtomicReference} (#817): an {@code AtomicReferenceFieldUpdater}, an
 * {@code AtomicReferenceArray} element and a {@code VarHandle}. The same two histories as
 * {@code AbaAgentFeedWeavingTest}: a toggle that ran wholly before the read is silent, one between
 * the read and the swap fires. Before #817 these slots' operations never reached the detector, so
 * both were silent.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM and this needs {@code fields=true}.
 */
@Tag("e2e")
class AbaSlotFeedWeavingTest {

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

    /** Runs {@code body} on a new thread inside {@code context}, as a runner worker would. */
    private static Thread inContext(AsyncTestContext context, String name, Runnable body) {
        Thread thread = new Thread(() -> {
            AsyncTestContext.install(context);
            try {
                body.run();
            } finally {
                AsyncTestContext.uninstall();
            }
        }, name);
        thread.start();
        return thread;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the other side never arrived");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Two workers swing the head from A to B and back, and a third reads it and then swaps A for
     * C; {@code toggleFirst} says whether the toggle runs wholly before the read or after it.
     * {@return the detector's report}
     */
    private static ABAProblemDetector.ABAReport run(int kind, boolean toggleFirst) throws InterruptedException {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        Node a = new Node("A");
        Node b = new Node("B");
        Node c = new Node("C");
        AbaSlotHeads.Head stack = AbaSlotHeads.all(a)[kind];
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch movedAway = new CountDownLatch(1);
        CountDownLatch movedBack = new CountDownLatch(1);
        AtomicReference<Boolean> swapped = new AtomicReference<>();

        Thread reader = inContext(context, "aba-reader", () -> {
            if (toggleFirst) {
                awaitQuietly(movedBack);
            }
            Node seen = stack.read();
            read.countDown();
            if (!toggleFirst) {
                awaitQuietly(movedBack);
            }
            swapped.set(stack.swap(seen, c));
        });
        Thread away = inContext(context, "aba-away", () -> {
            if (!toggleFirst) {
                awaitQuietly(read);
            }
            stack.store(b);
            movedAway.countDown();
        });
        Thread back = inContext(context, "aba-back", () -> {
            awaitQuietly(movedAway);
            stack.store(a);
            movedBack.countDown();
        });
        reader.join();
        away.join();
        back.join();
        assertTrue(swapped.get(), "the head was A again, so the swap must have succeeded");
        AsyncTestContext.install(context);
        try {
            return AsyncTestContext.abaProblemDetector().analyzeABA();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a woven toggle that ran wholly before the read is not an ABA, through every slot kind")
    void aToggleBeforeTheReadIsSilent() throws InterruptedException {
        for (int kind = 0; kind < 3; kind++) {
            ABAProblemDetector.ABAReport report = run(kind, true);
            assertFalse(report.hasIssues(), AbaSlotHeads.all(new Node("x"))[kind]
                    + ": the head went A to B to A before the reader read it. Report: " + report);
        }
    }

    @Test
    @DisplayName("the same woven toggle between the read and the swap is an ABA, through every slot kind")
    void aToggleBetweenTheReadAndTheSwapIsReported() throws InterruptedException {
        for (int kind = 0; kind < 3; kind++) {
            ABAProblemDetector.ABAReport report = run(kind, false);
            assertTrue(report.hasIssues() && report.toString().contains("expected A, set to C"),
                    AbaSlotHeads.all(new Node("x"))[kind] + ": the reader read A, the head went to B "
                            + "and back, and its swap from A succeeded; a silent report means this slot "
                            + "kind's woven calls never reached the detector. Report: " + report);
        }
    }
}
