package se.deversity.asynctest.example.service;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes work in discrete phases, synchronizing four worker threads at
 * the end of each phase using a {@link CyclicBarrier}.
 *
 * <p><strong>Bug:</strong> During phase 2 one worker in every four throws a
 * {@code RuntimeException} before reaching the barrier. That alone does not break
 * the barrier; it strands the workers already waiting for a fourth party. They wait
 * with a timeout, and a timed-out {@code await} <em>does</em> break the barrier.
 * Nothing ever calls {@code reset()}, so every later phase's {@code await} throws
 * {@link BrokenBarrierException} at once, and the processor never coordinates again.
 *
 * <p><strong>Fix:</strong> Reset the barrier (or replace it) once the failed phase has
 * been handled, or use a {@link java.util.concurrent.Phaser}, which tolerates a party
 * deregistering.
 */
public class BatchProcessor {

    private static final int PARTIES = 4;
    private static final long AWAIT_TIMEOUT_MS = 1_000;
    private final CyclicBarrier barrier = new CyclicBarrier(PARTIES);
    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * Executes the given phase. Phase 2 (0-based) simulates a worker that
     * throws before arriving at the barrier, stranding the others.
     */
    public void processPhase(int phaseNumber) throws InterruptedException, BrokenBarrierException {
        // Simulate work for this phase
        doPhaseWork(phaseNumber);

        // Bug: one out of every PARTIES calls during phase 2 throws before await
        int callIndex = callCount.getAndIncrement();
        if (phaseNumber == 2 && (callIndex % PARTIES) == 0) {
            throw new RuntimeException("Phase-2 failure: thread did not reach barrier");
        }

        try {
            barrier.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The timeout breaks the barrier, and nobody resets it.
            throw new IllegalStateException(
                    "phase " + phaseNumber + " timed out waiting for the other workers", e);
        }
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
