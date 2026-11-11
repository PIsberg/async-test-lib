package se.deversity.asynctest.agent;

import com.example.agentfixture.BarrierBean;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code CyclicBarrier.await} as a happens-before edge (#1246), judged end to end: weaver, hook,
 * model and {@code AtomicityValidator}.
 *
 * <p>The Java memory model orders what a party did before {@code await} before what every party
 * does after a successful return from the same generation. Each silent case writes on one worker
 * and updates on the other across a barrier; each twin keeps the same accesses with that order
 * missing, coordinated instead through latches in this unwoven test class, which the model cannot
 * see, and must keep its finding.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM and this one needs
 * {@code fields=true} and {@code collections=true}; {@code forkEvery=1} gives it its own JVM.
 */
@Tag("e2e")
class BarrierEdgeWeavingTest {

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

    /** A step a worker runs that may throw. */
    private interface Step {
        void run() throws Exception;
    }

    /** Runs both sides at once on two workers, and returns the validator's findings. */
    private static List<String> findings(Step one, Step other) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            CountDownLatch registered = new CountDownLatch(2);
            CountDownLatch done = new CountDownLatch(2);
            for (Step step : List.of(one, other)) {
                new Thread(() -> {
                    workers.add(Thread.currentThread().threadId());
                    registered.countDown();
                    try {
                        await(registered);
                        step.run();
                    } catch (Throwable t) { // NOPMD - reported to the test thread below
                        failed.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                }, "barrier-worker").start();
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        if (failed.get() != null) {
            throw new AssertionError("a worker failed, so nothing was measured", failed.get());
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

    private static boolean mentionsSlot(List<String> findings) {
        return findings.stream().anyMatch(f -> f.contains("Slot.result"));
    }

    @Test
    @DisplayName("a write before await is ordered before the other party's update after it")
    void theBarrierOrdersTheFill() throws InterruptedException {
        BarrierBean bean = new BarrierBean();
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> findings = findings(() -> bean.fillAndAwait(barrier, 1),
                () -> bean.awaitAndUpdate(barrier));

        assertFalse(mentionsSlot(findings),
                "One worker wrote the slot and then awaited; the other awaited and then updated "
                        + "it. CyclicBarrier orders every action before await before every action "
                        + "after a successful return. A finding here means the woven await fed no "
                        + "edge. Findings were: " + findings);
    }

    @Test
    @DisplayName("the same through await(long, TimeUnit)")
    void theTimedBarrierOrdersTheFill() throws InterruptedException {
        BarrierBean bean = new BarrierBean();
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> findings = findings(() -> bean.fillAndAwaitTimed(barrier, 1),
                () -> bean.awaitTimedAndUpdate(barrier));

        assertFalse(mentionsSlot(findings),
                "The timed overload is the same rendezvous when it returns normally. Findings "
                        + "were: " + findings);
    }

    @Test
    @DisplayName("the updater that skips the barrier keeps its finding")
    void skippingTheBarrierIsReported() throws InterruptedException {
        BarrierBean bean = new BarrierBean();
        CyclicBarrier alone = new CyclicBarrier(1);
        CountDownLatch filled = new CountDownLatch(1);
        List<String> findings = findings(() -> {
            bean.fillAndAwait(alone, 1);
            filled.countDown();
        }, () -> {
            await(filled);
            bean.update();
        });

        assertTrue(mentionsSlot(findings),
                "The writer passed a barrier of its own; the updater never awaited it, so nothing "
                        + "the program does orders the write before the update. Findings were: "
                        + findings);
    }

    @Test
    @DisplayName("a write between two generations is not ordered by either")
    void aWriteBetweenGenerationsIsReported() throws InterruptedException {
        BarrierBean bean = new BarrierBean();
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch filled = new CountDownLatch(1);
        List<String> findings = findings(() -> {
            bean.arrive(barrier);
            bean.fill(2);
            filled.countDown();
            bean.arrive(barrier);
        }, () -> {
            bean.arrive(barrier);
            await(filled);
            bean.update();
            bean.arrive(barrier);
        });

        assertTrue(mentionsSlot(findings),
                "The write came after the first generation and the update before the second, so "
                        + "neither return orders one before the other. Silence here means a "
                        + "release for the next generation leaked into the previous one. Findings "
                        + "were: " + findings);
    }

    @Test
    @DisplayName("a barrier that broke orders nothing")
    void aBrokenBarrierIsReported() throws InterruptedException {
        BarrierBean bean = new BarrierBean();
        CyclicBarrier barrier = new CyclicBarrier(3);
        CountDownLatch timedOut = new CountDownLatch(1);
        List<String> findings = findings(() -> {
            assertTrue(bean.fillAndTimeOut(barrier, 1), "the barrier was expected to time out");
            timedOut.countDown();
        }, () -> {
            assertTrue(bean.arriveExpectingBreak(barrier), "the barrier was expected to break");
            await(timedOut);
            bean.update();
        });

        assertTrue(mentionsSlot(findings),
                "The third party never came, so the writer timed out and the barrier broke; no "
                        + "await returned normally, and a broken generation orders nothing. "
                        + "Findings were: " + findings);
    }
}
