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
 * the end of each phase. During phase 2, one thread throws a RuntimeException
 * before calling barrier.await(). This breaks the barrier permanently: all other
 * threads that call await() — including future cycles — receive BrokenBarrierException.
 *
 * WHY @Test PASSES:
 * A single-threaded test either never reaches the broken code path (phaseNumber != 2)
 * or catches the exception locally. The barrier is never actually waited on
 * concurrently, so the broken state is never observable.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads running processPhase(2), the first thread to execute throws before
 * the barrier, breaking it. CyclicBarrierDetector records the recordBroken() event
 * and reports the issue at analysis time.
 *
 * FIX:
 * Call barrier.reset() after recovering from the exception, or use a Phaser
 * which tolerates party deregistration without permanently breaking.
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
        // CyclicBarrier(4) requires 4 parties; a single-thread invocation blocks
        // indefinitely on barrier.await(). Run on a daemon thread and verify no
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
     * With 8 threads all calling processPhase(2), one thread throws before
     * barrier.await(). CyclicBarrierDetector records the barrier broken event
     * and reports it at analysis time.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: reset the barrier after the exception, or use Phaser
     */
    @Disabled("Remove @Disabled: the round times out on the broken barrier, and the failure "
            + "names CyclicBarrierDetector's finding")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCyclicBarrierIssues = true, failOn = FailOn.LOW)
    void testProcessPhase_concurrent_detectsBrokenBarrier() {
        // Register the barrier so the detector knows its configuration
        AsyncTestContext.cyclicBarrierMonitor()
                .registerBarrier(processor.getBarrier(), "phase-barrier", 4);

        try {
            AsyncTestContext.cyclicBarrierMonitor()
                    .recordArrival(processor.getBarrier());
            processor.processPhase(2);
            AsyncTestContext.cyclicBarrierMonitor()
                    .recordBarrierComplete(processor.getBarrier());
        } catch (RuntimeException e) {
            // Thread threw before barrier.await() — barrier is now broken
            AsyncTestContext.cyclicBarrierMonitor()
                    .recordBroken(processor.getBarrier());
        } catch (Exception e) {
            AsyncTestContext.cyclicBarrierMonitor()
                    .recordBroken(processor.getBarrier());
        }
    }
}
