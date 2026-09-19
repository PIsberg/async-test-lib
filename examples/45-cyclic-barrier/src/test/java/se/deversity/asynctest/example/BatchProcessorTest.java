package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.BatchProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for BatchProcessor.
 *
 * ========================================================================
 * DETECTOR: CyclicBarrierDetector
 * ========================================================================
 *
 * THE BUG:
 * BatchProcessor uses a CyclicBarrier(4) to synchronize four worker threads at
 * the end of each phase. During phase 2, one worker in four throws a RuntimeException
 * before reaching the barrier, stranding the workers already waiting there. Their
 * await times out, which breaks the barrier, and nothing resets it: every later
 * phase's await throws BrokenBarrierException at once.
 *
 * WHY @Test PASSES:
 * A single-threaded test either never reaches the failing code path (phaseNumber != 2)
 * or catches the exception locally. The barrier is never actually waited on
 * concurrently, so it never breaks.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads each running phase 2 and then phase 3, the first round strands two
 * workers and their timeout breaks the barrier. Every worker then comes back to the
 * barrier for phase 3 after it already saw it broken - its own timeout, or a
 * BrokenBarrierException - with no reset in between, and CyclicBarrierDetector reports
 * that reuse. A break on its own is not the finding, and neither is one arrival that
 * hits it: breaking a barrier to cancel its parties, then dropping it, is correct.
 *
 * FIX:
 * Call barrier.reset() (or replace the barrier) once the failed phase is handled, or
 * use a Phaser, which tolerates a party deregistering.
 */
class BatchProcessorTest {

    private BatchProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new BatchProcessor();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testProcessPhase_phase0_doesNotThrowBeforeBarrier() throws Exception {
        // CyclicBarrier(4) requires 4 parties; a single-thread invocation waits at
        // the barrier until its timeout. Run on a daemon thread and verify no
        // exception escapes before the barrier wait.
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                processor.processPhase(0);
            } catch (Throwable th) {
                error.set(th);
            }
        });
        t.setDaemon(true);
        t.start();
        t.join(200);
        assertNull(error.get(), "No exception expected before barrier wait");
    }

    @Test
    void testProcessPhase_phase2_throwsBeforeBarrier() {
        // Single-threaded: the first call on phase 2 always throws (callIndex=0)
        assertThrows(RuntimeException.class, () -> processor.processPhase(2));
    }

    @Test
    void testGetBarrier_initialState_notBroken() {
        assertFalse(processor.getBarrier().isBroken(),
                "Barrier should not be broken before any concurrent use");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 threads each calling processPhase(2) and then processPhase(3), two of
     * every eight throw before the barrier, so two workers are stranded; their
     * timed-out await breaks the barrier and nothing resets it. Each worker comes back
     * for phase 3 to a barrier it already saw broken, and CyclicBarrierDetector reports
     * that by asking the barrier at the arrival.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: reset the barrier after the failed phase, or use Phaser
     */
    @Disabled("Remove @Disabled: a stranded worker's timed-out await breaks the barrier, later "
            + "rounds arrive at it broken, and the failure names CyclicBarrierDetector's finding")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCyclicBarrierIssues = true, failOn = FailOn.LOW)
    void testProcessPhase_concurrent_detectsBrokenBarrier() {
        var monitor = AsyncTestContext.cyclicBarrierMonitor();
        monitor.registerBarrier(processor.getBarrier(), "phase-barrier", 4);

        // A worker runs its phases in order. The detector asks the barrier at each arrival
        // and reports a worker coming back to a barrier it already saw broken; a recorded
        // break alone, or one arrival at a broken barrier, is not the finding.
        for (int phase = 2; phase <= 3; phase++) {
            monitor.recordArrival(processor.getBarrier());
            try {
                processor.processPhase(phase);
                monitor.recordBarrierComplete(processor.getBarrier());
            } catch (java.util.concurrent.BrokenBarrierException e) {
                monitor.recordBroken(processor.getBarrier());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                    monitor.recordTimeout(processor.getBarrier());   // this worker's timeout broke it
                }
                // Otherwise this worker threw before the barrier.
            }
        }
    }
}
