package se.deversity.asynctest.example.service;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes work in discrete phases, synchronizing four worker threads at
 * the end of each phase using a {@link CyclicBarrier}.
 *
 * <p><strong>Bug:</strong> During phase 2 one thread throws a
 * {@code RuntimeException} before reaching {@code barrier.await()}. This breaks
 * the barrier permanently: all other threads receive {@link BrokenBarrierException}
 * on their current or next await call.
 *
 * <p><strong>Fix:</strong> Reset the barrier after exception handling, or use a
 * {@link java.util.concurrent.Phaser} which tolerates party deregistration.
 */
public class BatchProcessor {

    private static final int PARTIES = 4;
    private final CyclicBarrier barrier = new CyclicBarrier(PARTIES);
    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * Executes the given phase. Phase 2 (0-based) simulates a thread that
     * throws before arriving at the barrier — breaking it for all parties.
     */
    public void processPhase(int phaseNumber) throws InterruptedException, BrokenBarrierException {
        // Simulate work for this phase
        doPhaseWork(phaseNumber);

        // Bug: one out of every PARTIES calls during phase 2 throws before await
        int callIndex = callCount.getAndIncrement();
        if (phaseNumber == 2 && (callIndex % PARTIES) == 0) {
            throw new RuntimeException("Phase-2 failure: thread did not reach barrier");
        }

        barrier.await();
    }

    /** Returns the underlying barrier for instrumentation in tests. */
    public CyclicBarrier getBarrier() {
        return barrier;
    }

    private void doPhaseWork(int phase) {
        long sum = 0;
        for (int i = 0; i < 500 * (phase + 1); i++) {
            sum += i;
        }
        if (sum < 0) throw new IllegalStateException("unreachable");
    }
}
