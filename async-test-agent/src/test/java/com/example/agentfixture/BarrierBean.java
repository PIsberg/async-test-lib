package com.example.agentfixture;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Plain fields published, or not, through a {@link CyclicBarrier} (#1246). Woven by the e2e test
 * that drives it, so every barrier call below goes through the agent's hook.
 */
public class BarrierBean {

    /** The plain field each case writes on one worker and updates on the other. */
    public static final class Slot {
        int result;
    }

    private final Slot slot = new Slot();

    /** Writes the slot. */
    public void fill(int value) {
        slot.result = value;
    }

    /** Updates the slot, a read and a write. */
    public int update() {
        return ++slot.result;
    }

    /** Waits at {@code barrier}. */
    public int arrive(CyclicBarrier barrier) throws InterruptedException, BrokenBarrierException {
        return barrier.await();
    }

    /** Writes the slot, then waits at {@code barrier}. */
    public void fillAndAwait(CyclicBarrier barrier, int value)
            throws InterruptedException, BrokenBarrierException {
        slot.result = value;
        barrier.await();
    }

    /** Waits at {@code barrier}, then updates the slot. */
    public int awaitAndUpdate(CyclicBarrier barrier)
            throws InterruptedException, BrokenBarrierException {
        barrier.await();
        return ++slot.result;
    }

    /** {@link #fillAndAwait} through the timed overload. */
    public void fillAndAwaitTimed(CyclicBarrier barrier, int value)
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        slot.result = value;
        barrier.await(10, TimeUnit.SECONDS);
    }

    /** {@link #awaitAndUpdate} through the timed overload. */
    public int awaitTimedAndUpdate(CyclicBarrier barrier)
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        barrier.await(10, TimeUnit.SECONDS);
        return ++slot.result;
    }

    /** Writes the slot and waits briefly at a barrier nobody else completes. */
    public boolean fillAndTimeOut(CyclicBarrier barrier, int value) throws InterruptedException {
        slot.result = value;
        try {
            barrier.await(200, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException | BrokenBarrierException expected) {
            return true;
        }
    }

    /** Waits at a barrier that breaks, and reports whether it did. */
    public boolean arriveExpectingBreak(CyclicBarrier barrier) throws InterruptedException {
        try {
            barrier.await();
            return false;
        } catch (BrokenBarrierException expected) {
            return true;
        }
    }
}
