package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link PhaserDetector} decides, on real {@link Phaser}s (#587).
 *
 * <p>Termination is how a phaser normally ends, and a timed wait that expires and is handled is
 * ordinary code, so neither is a finding on its own. The findings are the two things a phaser
 * can be observed doing wrong: a party arriving (or registering) at a phaser whose parties had
 * all deregistered, and a timed wait whose phase never advanced before the run ended.
 */
@DisplayName("PhaserDetector model (#587)")
class PhaserDetectorModelTest {

    @Test
    @DisplayName("every party arriving and deregistering to zero terminates the phaser, and is silent")
    void arriveAndDeregisterToZeroStaysSilent() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);
        detector.registerPhaser(phaser, "two-parties", 2);

        detector.recordArrival(phaser, phaser.arriveAndDeregister());
        detector.recordArrival(phaser, phaser.arriveAndDeregister());
        assertTrue(phaser.isTerminated(), "the last deregistration terminates a root phaser");
        detector.recordTermination(phaser);

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "arriveAndDeregister to zero is how a phaser is meant to end. Report:\n" + report);
    }

    @Test
    @DisplayName("an arrive after every party deregistered fires")
    void arriveAfterTerminationFires() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(1);
        detector.registerPhaser(phaser, "one-party", 1);

        detector.recordArrival(phaser, phaser.arriveAndDeregister());
        int late = phaser.arrive();   // the bug: a second party the count never included
        assertTrue(late < 0, "a terminated phaser answers an arrival with a negative phase");
        detector.recordArrival(phaser, late);

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the arrival synchronised with nobody: the phaser had already ended because "
                        + "every registered party had left. Report:\n" + report);
    }

    @Test
    @DisplayName("a register after every party deregistered fires")
    void registerAfterTerminationFires() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(1);
        detector.registerPhaser(phaser, "late-joiner", 1);

        phaser.arriveAndDeregister();
        detector.recordArrival(phaser, phaser.register());

        assertTrue(detector.analyze().hasIssues(),
                "a party joining a phaser that already ended is never coordinated with anyone");
    }

    @Test
    @DisplayName("an arrival after forceTermination is a cancellation, and is silent")
    void arrivalAfterForcedTerminationStaysSilent() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);
        detector.registerPhaser(phaser, "cancelled", 2);

        phaser.forceTermination();
        detector.recordTermination(phaser);
        detector.recordArrival(phaser, phaser.arrive());

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "forceTermination is the intended way to cancel a phaser, and a party that then "
                        + "sees a negative phase is being told so. Report:\n" + report);
    }

    @Test
    @DisplayName("the Phaser javadoc's onAdvance loop ends in termination with parties registered, and is silent")
    void onAdvanceTerminationStaysSilent() throws InterruptedException {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(1) {
            @Override
            protected boolean onAdvance(int phase, int registeredParties) {
                return phase >= 2;
            }
        };
        detector.registerPhaser(phaser, "rounds", 1);
        Thread[] tasks = new Thread[2];
        for (int t = 0; t < tasks.length; t++) {
            detector.recordArrival(phaser, phaser.register());
            tasks[t] = new Thread(() -> {
                do {
                    detector.recordArrival(phaser, phaser.arriveAndAwaitAdvance());
                } while (!phaser.isTerminated());
            });
            tasks[t].setDaemon(true);
            tasks[t].start();
        }
        detector.recordArrival(phaser, phaser.arriveAndDeregister());
        for (Thread task : tasks) {
            task.join(10_000);
            assertFalse(task.isAlive(), "the rounds must end in termination");
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "the last arriveAndAwaitAdvance of each task returns a negative phase because "
                        + "onAdvance ended the rounds, which is the javadoc's own pattern. Report:\n"
                        + report);
    }

    @Test
    @DisplayName("a handled timeout whose phase later advanced is silent")
    void handledTimeoutThatLaterAdvancedStaysSilent() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);
        detector.registerPhaser(phaser, "slow-partner", 2);

        detector.recordArrival(phaser, phaser.arrive());
        assertThrows(TimeoutException.class,
                () -> phaser.awaitAdvanceInterruptibly(0, 1, TimeUnit.MILLISECONDS));
        detector.recordTimeout(phaser);   // handled: the caller polls again
        detector.recordArrival(phaser, phaser.arrive());   // the partner arrives late
        detector.recordPhaseComplete(phaser, 0);

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "the phase the wait timed out on advanced afterwards, so the timeout was a "
                        + "wait shorter than the partner, not a party that never arrived. Report:\n"
                        + report);
    }

    @Test
    @DisplayName("a timeout on a phase that never advanced before analysis fires")
    void timeoutOnAPhaseThatNeverAdvancedFires() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(2);
        detector.registerPhaser(phaser, "missing-party", 2);

        detector.recordArrival(phaser, phaser.arrive());   // the second party never arrives
        assertThrows(TimeoutException.class,
                () -> phaser.awaitAdvanceInterruptibly(0, 1, TimeUnit.MILLISECONDS));
        detector.recordTimeout(phaser);

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                "a registered party never arrived, so the phase the wait gave up on is still the "
                        + "current phase at analysis. Report:\n" + report);
    }

    @Test
    @DisplayName("recordPhaseComplete(phaser, 0) is counted")
    void phaseZeroCompletionIsCounted() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(1);
        detector.registerPhaser(phaser, "counted", 1);
        detector.recordArrival(phaser, phaser.arrive());
        detector.recordPhaseComplete(phaser, 0);
        detector.recordTimeout(phaser);   // phase 1 never advances: the report renders the phaser

        String rendered = detector.analyze().toString();
        assertTrue(rendered.contains("1 phase completion"),
                "phase 0 is a phase like any other. Report:\n" + rendered);
    }
}
