package se.deversity.asynctest.example.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates task execution using multiple synchronization primitives.
 *
 * <p><strong>Bug:</strong> {@link #execute(Runnable)} acquires a {@link Semaphore},
 * then a {@link ReentrantLock}, then counts down a {@link CountDownLatch} — three
 * independent primitives for a single operation. This creates unnecessary complexity:
 * an exception between any two acquisitions leaves the others in an inconsistent state,
 * and the acquisition order is not enforced across threads, introducing latent deadlock risk.
 *
 * <p><strong>Fix:</strong> Use a single {@link ReentrantLock} for mutual exclusion.
 * If signalling is required, use a {@link java.util.concurrent.locks.Condition} on the
 * same lock instead of a separate latch.
 */
public class CoordinationService {

    private final Semaphore semaphore = new Semaphore(1);
    private final ReentrantLock lock = new ReentrantLock();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    /**
     * Executes a task after acquiring all three synchronizers in sequence.
     * Bug: over-synchronized — any exception between acquisitions leaves the state broken.
     */
    public void execute(Runnable task) throws InterruptedException {
        semaphore.acquire();
        try {
            lock.lock();
            try {
                task.run();
            } finally {
                lock.unlock();
            }
        } finally {
            latch.countDown();
            semaphore.release();
        }
        // Reset for the next use (fragile: race between release and next acquire)
        latch = new CountDownLatch(1);
    }

    /** Returns the semaphore for test instrumentation. */
    public Semaphore getSemaphore() { return semaphore; }

    /** Returns the lock for test instrumentation. */
    public ReentrantLock getLock() { return lock; }

    /** Returns the current latch for test instrumentation. */
    public CountDownLatch getLatch() { return latch; }
}
