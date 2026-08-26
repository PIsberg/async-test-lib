package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.MultiPhaseProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Phaser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for MultiPhaseProcessor.
 *
 * ========================================================================
 * DETECTOR: PhaserDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * MultiPhaseProcessor creates a Phaser(2) — registering only 2 parties — but
 * many threads call arriveAndAwaitAdvance(). When the arrival count exceeds
 * the registered-party count the Phaser either throws IllegalStateException
 * or terminates unexpectedly, leaving all waiting threads blocked.
 *
 * WHY @Test PASSES:
 * A single thread never arrives more than once per advance, so the party-count
 * mismatch is never triggered. The phaser advances normally and the test exits.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads simultaneously call runPhase(). Each calls arriveAndAwaitAdvance()
 * on a Phaser that only knows about 2 parties. The overflow causes a timeout
 * or termination. PhaserDetector records all arrive() calls, compares them to
 * the registered party count, and reports the discrepancy.
 *
 * DETECTORS TRIGGERED:
 *   PhaserDetector — primary: detects timeout or termination of the phaser
 *
 * FIX: use new Phaser(threadCount) or call phaser.register() for every thread
 *      that will participate in the phase.
 */
class MultiPhaseProcessorTest {

    private MultiPhaseProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MultiPhaseProcessor();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testRunPhase_singleThread_advancesNormally() {
        // With only 2 parties registered and one thread, the phaser never fully
        // advances (needs 2 arrivals). Verify getPhase() returns 0.
        Phaser phaser = processor.getPhaser();
        assertNotNull(phaser, "Phaser must be non-null");
        assertEquals(0, phaser.getPhase(), "Phase should start at 0");
    }

    @Test
    void testRunPhase_phaserInitialState_isCorrect() {
        Phaser phaser = processor.getPhaser();
        assertEquals(2, phaser.getRegisteredParties(),
                "Phaser should be initialised with 2 registered parties");
        assertFalse(phaser.isTerminated(), "Phaser must not be terminated at start");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes phaser party-count mismatch
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see the short party count detected by PhaserDetector")
    @AsyncTest(threads = 3, invocations = 1, detectAll = false,
            detectPhaserIssues = true, failOn = FailOn.LOW)

    void testRunPhase_concurrent_detectsPhaserMisuse() throws InterruptedException {
        Phaser phaser = processor.getPhaser();
        var detector = AsyncTestContext.phaserMonitor();

        detector.registerPhaser(phaser, "multi-phase-processor-phaser", 2);
        detector.recordArriveAwaitAdvance(phaser);

        // Three threads, two registered parties. Two of them pair up and advance the phase; the
        // third has nobody left to pair with and sits in arriveAndAwaitAdvance() for good. That
        // is the short registration, and it is deterministic at three threads in a way it was
        // not at eight: with an even number everybody pairs off, so whether anything went wrong
        // came down to whether concurrent arrivals happened to overshoot the party count. This
        // demonstration timed out in two runs of three and passed in the other. See issue #363.
        //
        // The subject runs on a thread of its own so the wait can be given a deadline.
        // arriveAndAwaitAdvance() does not respond to interrupts, so a stranded party can only
        // be abandoned; the thread is a daemon for that reason and costs nothing after the JVM
        // is done.
        Thread party = new Thread(() -> processor.runPhase(0), "phase-party");
        party.setDaemon(true);
        party.start();
        party.join(200);

        if (party.isAlive()) {
            // This party will never advance: recordTimeout is what PhaserDetector.hasIssues()
            // gates on, and an arrival that never completes is the finding.
            detector.recordTimeout(phaser);
        } else {
            detector.recordPhaseComplete(phaser, phaser.getPhase());
        }
    }
}
