package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
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

    @Disabled("Remove @Disabled to see phaser party mismatch detected by PhaserDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectPhaserIssues = true)
    void testRunPhase_concurrent_detectsPhaserMisuse() {
        Phaser phaser = processor.getPhaser();

        // Register with the detector (2 parties declared, but 8 threads arrive)
        AsyncTestContext.phaserMonitor()
                .registerPhaser(phaser, "multi-phase-processor-phaser", 2);

        // Record the arrive+await call that will exceed the registered count
        AsyncTestContext.phaserMonitor()
                .recordArriveAwaitAdvance(phaser);

        // Perform the buggy phase operation — will cause timeout/termination
        try {
            processor.runPhase(0);
        } catch (IllegalStateException e) {
            // Record that the phaser terminated due to the excess arrival
            AsyncTestContext.phaserMonitor().recordTermination(phaser);
        }
    }
}
