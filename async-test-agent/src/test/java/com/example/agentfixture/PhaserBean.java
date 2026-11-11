package com.example.agentfixture;

import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Plain fields published, or not, through a {@link Phaser} (#1265). Woven by the e2e test that
 * drives it, so every phaser call below goes through the agent's hook.
 */
public class PhaserBean {

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

    /** Arrives and waits for the others. */
    public int arriveAndAwait(Phaser phaser) {
        return phaser.arriveAndAwaitAdvance();
    }

    /** Writes the slot, then arrives and waits for the others. */
    public void fillAndArriveAndAwait(Phaser phaser, int value) {
        slot.result = value;
        phaser.arriveAndAwaitAdvance();
    }

    /** Arrives and waits for the others, then updates the slot. */
    public int arriveAndAwaitAndUpdate(Phaser phaser) {
        phaser.arriveAndAwaitAdvance();
        return ++slot.result;
    }

    /** Writes the slot, then arrives without waiting. */
    public void fillAndArrive(Phaser phaser, int value) {
        slot.result = value;
        phaser.arrive();
    }

    /** Writes the slot, then arrives and deregisters. */
    public void fillAndDeregister(Phaser phaser, int value) {
        slot.result = value;
        phaser.arriveAndDeregister();
    }

    /** Arrives, waits for the phase it arrived in to advance, then updates the slot. */
    public int arriveThenAwaitAdvanceAndUpdate(Phaser phaser) {
        phaser.awaitAdvance(phaser.arrive());
        return ++slot.result;
    }

    /** Arrives without waiting, and returns the arrival phase. */
    public int arrive(Phaser phaser) {
        return phaser.arrive();
    }

    /** Waits for {@code phase} to advance, then updates the slot. */
    public int awaitAdvanceAndUpdate(Phaser phaser, int phase) {
        phaser.awaitAdvance(phase);
        return ++slot.result;
    }

    /** {@link #arriveThenAwaitAdvanceAndUpdate} through the timed interruptible form. */
    public int arriveThenAwaitTimedAndUpdate(Phaser phaser)
            throws InterruptedException, TimeoutException {
        phaser.awaitAdvanceInterruptibly(phaser.arrive(), 10, TimeUnit.SECONDS);
        return ++slot.result;
    }
}
