package se.deversity.asynctest.agent;

import com.example.agentfixture.PhaserBean;
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
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code Phaser} advance as a happens-before edge (#1265), judged end to end: weaver, hook,
 * model and {@code AtomicityValidator}.
 *
 * <p>The {@code Phaser} javadoc orders every arrival in a phase before every return from waiting
 * for that phase to advance. Each silent case writes on one worker and updates on the other across
 * a phase; each twin keeps the same accesses with that order missing, coordinated instead through
 * latches in this unwoven test class, which the model cannot see, and must keep its finding.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM and this one needs
 * {@code fields=true} and {@code collections=true}; {@code forkEvery=1} gives it its own JVM.
 */
@Tag("e2e")
class PhaserEdgeWeavingTest {

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
                }, "phaser-worker").start();
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
    @DisplayName("a write before arriveAndAwaitAdvance is ordered before the other party's update after it")
    void theAdvanceOrdersTheFill() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser phaser = new Phaser(2);
        List<String> findings = findings(() -> bean.fillAndArriveAndAwait(phaser, 1),
                () -> bean.arriveAndAwaitAndUpdate(phaser));

        assertFalse(mentionsSlot(findings),
                "One worker wrote the slot and then arrived; the other arrived, waited for the "
                        + "advance and then updated it. A finding here means the woven "
                        + "arriveAndAwaitAdvance fed no edge. Findings were: " + findings);
    }

    @Test
    @DisplayName("an arrive without waiting is ordered before an awaitAdvance of its phase")
    void anArriveOrdersTheAwaitAdvance() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser phaser = new Phaser(2);
        List<String> findings = findings(() -> bean.fillAndArrive(phaser, 1),
                () -> bean.arriveThenAwaitAdvanceAndUpdate(phaser));

        assertFalse(mentionsSlot(findings),
                "arrive is a release into its phase and awaitAdvance of that phase the acquire. "
                        + "Findings were: " + findings);
    }

    @Test
    @DisplayName("the same through awaitAdvanceInterruptibly(int, long, TimeUnit)")
    void theTimedAwaitOrdersTheFill() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser phaser = new Phaser(2);
        List<String> findings = findings(() -> bean.fillAndArrive(phaser, 1),
                () -> bean.arriveThenAwaitTimedAndUpdate(phaser));

        assertFalse(mentionsSlot(findings),
                "The timed form is the same acquire when the phase advanced. Findings were: "
                        + findings);
    }

    @Test
    @DisplayName("an arriveAndDeregister is a release into the phase it arrives in")
    void aDeregisteringArrivalOrdersTheFill() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser phaser = new Phaser(2);
        List<String> findings = findings(() -> bean.fillAndDeregister(phaser, 1),
                () -> bean.arriveAndAwaitAndUpdate(phaser));

        assertFalse(mentionsSlot(findings),
                "The writer's last arrival still counts toward the phase the updater waited "
                        + "for. Findings were: " + findings);
    }

    @Test
    @DisplayName("children of one root advance together, so they order each other")
    void tieredPhasersOrderAcrossChildren() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser root = new Phaser();
        Phaser left = new Phaser(root, 1);
        Phaser right = new Phaser(root, 1);
        List<String> findings = findings(() -> bean.fillAndArriveAndAwait(left, 1),
                () -> bean.arriveAndAwaitAndUpdate(right));

        assertFalse(mentionsSlot(findings),
                "Each worker arrived on its own child; the root advances only when both have, "
                        + "so the write is ordered before the update. Findings were: " + findings);
    }

    @Test
    @DisplayName("the updater that skips the phaser keeps its finding")
    void skippingThePhaserIsReported() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser alone = new Phaser(1);
        CountDownLatch filled = new CountDownLatch(1);
        List<String> findings = findings(() -> {
            bean.fillAndArriveAndAwait(alone, 1);
            filled.countDown();
        }, () -> {
            await(filled);
            bean.update();
        });

        assertTrue(mentionsSlot(findings),
                "The writer advanced a phaser of its own; the updater never waited on it, so "
                        + "nothing the program does orders the write before the update. Findings "
                        + "were: " + findings);
    }

    @Test
    @DisplayName("a write between two phases is not ordered by either")
    void aWriteBetweenPhasesIsReported() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser phaser = new Phaser(2);
        CountDownLatch filled = new CountDownLatch(1);
        List<String> findings = findings(() -> {
            bean.arriveAndAwait(phaser);
            bean.fill(2);
            filled.countDown();
            bean.arriveAndAwait(phaser);
        }, () -> {
            bean.arriveAndAwait(phaser);
            await(filled);
            bean.update();
            bean.arriveAndAwait(phaser);
        });

        assertTrue(mentionsSlot(findings),
                "The write came after the first advance and the update before the second, so "
                        + "neither orders one before the other. Silence here means a release "
                        + "into the next phase leaked into the previous one. Findings were: "
                        + findings);
    }

    @Test
    @DisplayName("an arrival in the next phase does not reach a wait on the previous one")
    void aReleaseIntoTheNextPhaseIsReported() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser phaser = new Phaser(2);
        CountDownLatch bothArrived = new CountDownLatch(1);
        CountDownLatch filled = new CountDownLatch(1);
        List<String> findings = findings(() -> {
            bean.arrive(phaser);
            await(bothArrived);
            bean.fill(2);
            bean.arrive(phaser);
            filled.countDown();
        }, () -> {
            bean.arrive(phaser);
            bothArrived.countDown();
            await(filled);
            bean.awaitAdvanceAndUpdate(phaser, 0);
        });

        assertTrue(mentionsSlot(findings),
                "The write precedes an arrival in phase 1, and the update follows a wait on phase "
                        + "0, which had advanced before the write. Unlike the case above this does "
                        + "not depend on timing: silence means phases share one clock. Findings "
                        + "were: " + findings);
    }

    @Test
    @DisplayName("a terminated phaser orders nothing")
    void aTerminatedPhaserIsReported() throws InterruptedException {
        PhaserBean bean = new PhaserBean();
        Phaser phaser = new Phaser(2);
        phaser.forceTermination();
        CountDownLatch filled = new CountDownLatch(1);
        List<String> findings = findings(() -> {
            bean.fillAndArriveAndAwait(phaser, 1);
            filled.countDown();
        }, () -> {
            await(filled);
            bean.arriveAndAwaitAndUpdate(phaser);
        });

        assertTrue(mentionsSlot(findings),
                "Both calls returned at once on a terminated phaser, which waits for nobody, so "
                        + "nothing orders the write before the update. Findings were: "
                        + findings);
    }
}
