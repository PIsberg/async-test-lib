package se.deversity.asynctest.agent;

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
 * {@code ABAProblemDetector} fed by the agent, end to end: weaver, the {@code AtomicReference}
 * hooks and the detector's view of the atomic (#817).
 *
 * <p>Recording by hand, a toggle that ran wholly before a read but was recorded after it has the
 * records of a real A-B-A, and the detector reports both ({@code ABAProblemDetectorTest} pins
 * that). Woven, each record is taken inside its operation, so the two histories differ: the toggle
 * that ran before the read is silent and the one that ran after it fires. Nothing here records;
 * every observation comes from the woven calls in {@link AbaStackBean}.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM and this one needs
 * {@code fields=true}; {@code forkEvery=1} gives it its own JVM.
 */
@Tag("e2e")
class AbaAgentFeedWeavingTest {

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
    private static ABAProblemDetector.ABAReport run(boolean toggleFirst) throws InterruptedException {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        Node a = new Node("A");
        Node b = new Node("B");
        Node c = new Node("C");
        AbaStackBean stack = new AbaStackBean(a);
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
    @DisplayName("a woven toggle that ran wholly before the read is not an ABA")
    void aToggleBeforeTheReadIsSilent() throws InterruptedException {
        ABAProblemDetector.ABAReport report = run(true);
        assertFalse(report.hasIssues(),
                "The head went A to B to A before the reader read it, so the reader's A is the "
                        + "value after the toggle and its swap is sound. Woven, each record is "
                        + "taken inside its operation, so the toggle cannot be recorded after the "
                        + "read (#817). Report: " + report);
    }

    @Test
    @DisplayName("the same woven toggle between the read and the swap is an ABA")
    void aToggleBetweenTheReadAndTheSwapIsReported() throws InterruptedException {
        ABAProblemDetector.ABAReport report = run(false);
        assertTrue(report.hasIssues() && report.toString().contains("expected A, set to C"),
                "The reader read A, the head went to B and back to A, and the reader's swap from "
                        + "A succeeded: the A-B-A a pop that pushes a node back suffers. Nothing "
                        + "recorded it by hand, so a silent report means the woven calls never "
                        + "reached the detector. Report: " + report);
    }
}
