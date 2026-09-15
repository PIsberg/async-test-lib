package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A phase stalled by a party that never arrives, with no timed wait recorded on it (#602).
 *
 * <p>Every other party is blocked in an untimed {@code arriveAndAwaitAdvance()}, which records
 * nothing on its own. The body records the start of the wait and its return; a wait that never
 * returned, on a phase that is still current with a party not arrived, whose thread is still
 * parked inside the phaser, is the stall.
 */
@DisplayName("PhaserDetector stalled phase without a timeout (#602)")
class PhaserStalledPhaseTest {

    private static final long PARK_DEADLINE_MS = 10_000;

    /** Starts a daemon party that records its wait around a real arriveAndAwaitAdvance. */
    private static Thread waitingParty(PhaserDetector detector, Phaser phaser, String name) {
        Thread party = new Thread(() -> {
            detector.recordAwaitAdvanceStarted(phaser);
            int phase = phaser.arriveAndAwaitAdvance();
            detector.recordAwaitAdvanceReturned(phaser, phase);
        }, name);
        // arriveAndAwaitAdvance does not respond to interrupts: a stranded party can only be
        // abandoned, so it must not keep the test JVM alive.
        party.setDaemon(true);
        party.start();
        return party;
    }

    private static void awaitParked(Phaser phaser, Thread party, int arrived) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PARK_DEADLINE_MS);
        while (!(phaser.getArrivedParties() == arrived && party.getState() == Thread.State.WAITING)) {
            assertTrue(System.nanoTime() < deadline, "the party never parked in the phaser");
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("a party that returns early without arriving strands the waiter, and fires")
    void partySkippingItsArrivalFires() throws InterruptedException {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);
        detector.registerPhaser(phaser, "two-parties", 2);

        Thread waiter = waitingParty(detector, phaser, "waiter");
        awaitParked(phaser, waiter, 1);
        // The second party takes an early-return path and never calls arriveAndAwaitAdvance.

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                "a registered party never arrived, the waiter never returned, and phase 0 is still "
                        + "current. Report:\n" + report);
        assertTrue(report.toString().contains("never returned"),
                "the report names the unreturned wait. Report:\n" + report);
    }

    @Test
    @DisplayName("the same shape with the arrival in finally is silent")
    void arrivalInFinallyStaysSilent() throws InterruptedException {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);
        detector.registerPhaser(phaser, "two-parties", 2);

        Thread waiter = waitingParty(detector, phaser, "waiter");
        awaitParked(phaser, waiter, 1);
        try {
            // the early-return path
        } finally {
            detector.recordAwaitAdvanceStarted(phaser);
            detector.recordAwaitAdvanceReturned(phaser, phaser.arriveAndAwaitAdvance());
        }
        waiter.join(PARK_DEADLINE_MS);
        assertFalse(waiter.isAlive(), "both parties arrived, so the phase advanced");

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "every wait returned. Report:\n" + report);
    }

    @Test
    @DisplayName("registering per round and only arriving, never deregistering, is silent")
    void registerPerRoundLeftoversStaySilent() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser();
        detector.registerPhaser(phaser, "per-round", 0);
        for (int round = 0; round < 3; round++) {
            detector.recordArrival(phaser, phaser.register());
            detector.recordArrival(phaser, phaser.register());
            detector.recordArrival(phaser, phaser.arrive());   // one of the two arrives
        }
        assertTrue(phaser.getUnarrivedParties() > 0 && phaser.getArrivedParties() > 0,
                "the shape a naive arrived-and-unarrived check would report");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "leftover registrations with nobody waiting are not a stall. Report:\n" + report);
    }

    @Test
    @DisplayName("a recorded start whose call threw, on a thread that moved on, is silent")
    void startWhoseCallThrewStaysSilent() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser();
        detector.registerPhaser(phaser, "unregistered-arrival", 0);

        detector.recordAwaitAdvanceStarted(phaser);
        assertThrows(IllegalStateException.class, phaser::arriveAndAwaitAdvance,
                "arriving at a phaser with no registered party is refused");
        phaser.register();   // now phase 0 has a party not arrived

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "the thread never waited: it is running, not parked in the phaser. Report:\n" + report);
    }

    @Test
    @DisplayName("a waiter whose phase advanced after a later start is silent")
    void waiterReleasedByALateArrivalStaysSilent() throws InterruptedException {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);
        detector.registerPhaser(phaser, "late-partner", 2);
        CountDownLatch released = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            detector.recordAwaitAdvanceStarted(phaser);
            phaser.arriveAndAwaitAdvance();
            released.countDown();   // returned, but the body never recorded the return
        }, "waiter");
        waiter.setDaemon(true);
        waiter.start();
        awaitParked(phaser, waiter, 1);
        phaser.arrive();
        assertTrue(released.await(PARK_DEADLINE_MS, TimeUnit.MILLISECONDS));

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "the phase advanced, so no party was left short, return recorded or not. Report:\n"
                        + report);
    }
}
