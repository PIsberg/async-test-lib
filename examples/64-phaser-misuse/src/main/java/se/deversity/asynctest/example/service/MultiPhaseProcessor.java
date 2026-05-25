package se.deversity.asynctest.example.service;

import java.util.concurrent.Phaser;

/**
 * BUGGY service that demonstrates Phaser party-count misuse.
 *
 * BUG: The Phaser is created with 2 registered parties, but the test drives
 *      8 threads through runPhase(). When a third (or more) thread calls
 *      arriveAndAwaitAdvance() without being registered as a party, the Phaser
 *      either throws IllegalStateException or terminates prematurely, leaving
 *      other threads blocked at the phase boundary.
 *
 * FIX: Register the correct number of parties upfront — new Phaser(threadCount)
 *      — or use phaser.register() / phaser.bulkRegister(n) before each batch.
 */
public class MultiPhaseProcessor {

    // BUG: only 2 parties registered, but many threads will call arrive*()
    private final Phaser phaser = new Phaser(2);

    /**
     * Simulate a phase of work and synchronise at the phase boundary.
     * Thread-unsafe: more threads arrive than the phaser has parties for.
     */
    public void runPhase(int phase) {
        // Simulate work
        int sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += i;
        }
        // BUG: unregistered threads call arriveAndAwaitAdvance(), violating
        //      the registered-party count contract
        phaser.arriveAndAwaitAdvance();
    }

    /** Exposed so the test can register the phaser with the detector. */
    public Phaser getPhaser() {
        return phaser;
    }
}
