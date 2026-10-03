package se.deversity.asynctest.agent;

import com.example.agentfixture.AbaStackBean;
import com.example.agentfixture.StampedHeadBean;
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
 * {@code ABAProblemDetector} fed by the agent through an {@code AtomicStampedReference} (#817),
 * whose value is the (reference, stamp) pair. Bumping the stamp on every store is the defence:
 * the pair that left never comes back, and a stale compare-and-set fails. Reusing it is the bug,
 * and is the A-B-A of a bare reference again.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM and this needs {@code fields=true}.
 */
@Tag("e2e")
class AbaStampedFeedWeavingTest {

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
     * Two workers swing the head from A to B and back, bumping the stamp when {@code bump} and
     * keeping it otherwise, and a third reads the pair and swaps A for C expecting the stamp it read;
     * {@code toggleFirst} says whether the toggle runs wholly before the read.
     */
    private static Result run(boolean bump, boolean toggleFirst) throws InterruptedException {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        Node a = new Node("A");
        Node b = new Node("B");
        Node c = new Node("C");
        StampedHeadBean stack = new StampedHeadBean(a);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch movedAway = new CountDownLatch(1);
        CountDownLatch movedBack = new CountDownLatch(1);
        AtomicReference<Boolean> swapped = new AtomicReference<>();

        Thread reader = inContext(context, "aba-reader", () -> {
            if (toggleFirst) {
                awaitQuietly(movedBack);
            }
            int[] stamp = new int[1];
            Node seen = stack.read(stamp);
            read.countDown();
            if (!toggleFirst) {
                awaitQuietly(movedBack);
            }
            swapped.set(stack.swap(seen, c, stamp[0]));
        });
        Thread away = inContext(context, "aba-away", () -> {
            if (!toggleFirst) {
                awaitQuietly(read);
            }
            stack.store(b, bump ? stack.stamp() + 1 : stack.stamp());
            movedAway.countDown();
        });
        Thread back = inContext(context, "aba-back", () -> {
            awaitQuietly(movedAway);
            stack.store(a, bump ? stack.stamp() + 1 : stack.stamp());
            movedBack.countDown();
        });
        reader.join();
        away.join();
        back.join();
        AsyncTestContext.install(context);
        try {
            return new Result(swapped.get(), AsyncTestContext.abaProblemDetector().analyzeABA());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    private record Result(boolean swapped, ABAProblemDetector.ABAReport report) { }

    @Test
    @DisplayName("a stamp reused across an A-B-A between the read and the swap is reported")
    void aReusedStampIsAnABA() throws InterruptedException {
        Result result = run(false, false);
        assertTrue(result.swapped(), "the pair was (A, 0) again, so the swap succeeded");
        assertTrue(result.report().hasIssues() && result.report().toString().contains("stamp"),
                "the reader read (A, 0), the head went to B and back to A with the stamp unchanged, and "
                        + "the swap expecting (A, 0) succeeded: the A-B-A the stamp exists to stop. "
                        + "Report: " + result.report());
    }

    @Test
    @DisplayName("a stamp bumped on every store makes the stale swap fail, and nothing is reported")
    void aBumpedStampStopsTheABA() throws InterruptedException {
        Result result = run(true, false);
        assertFalse(result.swapped(), "the stamp moved on, so a swap expecting the old one fails");
        assertFalse(result.report().hasIssues(), "the defence worked: " + result.report());
    }

    @Test
    @DisplayName("a reused-stamp toggle that ran wholly before the read is not an ABA")
    void aToggleBeforeTheReadIsSilent() throws InterruptedException {
        Result result = run(false, true);
        assertTrue(result.swapped());
        assertFalse(result.report().hasIssues(), "the reader read the pair after the toggle: " + result.report());
    }
}
