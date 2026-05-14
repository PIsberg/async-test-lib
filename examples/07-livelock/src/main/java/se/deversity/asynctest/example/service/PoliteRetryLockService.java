package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Distributed lock retry service that "politely" backs off when it detects contention.
 *
 * BUG: When two nodes simultaneously try to acquire the same lock, both detect
 * the contention, both immediately release, and both immediately retry — creating
 * a symmetric livelock where every node is perpetually active but no node ever
 * holds the lock long enough to complete its critical section.
 *
 * Unlike a deadlock (all threads blocked), a livelock keeps all threads RUNNABLE:
 * they are busy spinning through the acquire-detect-release-retry cycle, consuming
 * CPU without making any forward progress.
 *
 * LivelockDetector observes repeated thread-state transitions and CPU-time growth
 * without corresponding work completion, flagging the threads as livelock candidates.
 *
 * FIX: Introduce a randomised exponential back-off delay before each retry:
 *   {@code Thread.sleep(ThreadLocalRandom.current().nextLong(minMs, maxMs))}
 * Different random delays break the lock-step symmetry — one node waits longer and
 * the other acquires the lock, makes progress, and releases it before the first
 * node retries.
 */
public class PoliteRetryLockService {

    /**
     * Represents the distributed lock: holds the ID of the node currently owning it,
     * or {@code null} when the lock is free.
     */
    private final AtomicReference<String> lockHolder = new AtomicReference<>(null);

    /**
     * Tracks the number of times a lock acquisition completed successfully.
     * Used in tests to verify that work actually progressed.
     */
    private volatile int acquisitionCount = 0;

    /**
     * Maximum number of retry attempts before giving up.
     * Kept small in examples to prevent infinite loops in tests.
     */
    private static final int MAX_RETRIES = 200;

    /**
     * Attempts to acquire the distributed lock for {@code nodeId}.
     *
     * BUG: Uses a "polite yield" strategy — if the lock is already held, immediately
     * release it and retry from the beginning. Under contention with N nodes, all N
     * nodes detect each other simultaneously, all release, all retry at the same time,
     * and the cycle repeats forever.
     *
     * @param nodeId the unique identifier of the requesting node
     * @return {@code true} if the lock was acquired and the critical section completed;
     *         {@code false} if {@code MAX_RETRIES} attempts were exhausted
     */
    public boolean acquireLock(String nodeId) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            // Try to take the lock (CAS: null → nodeId)
            if (lockHolder.compareAndSet(null, nodeId)) {
                // Simulate a short critical section
                performCriticalWork(nodeId);
                lockHolder.set(null); // release
                acquisitionCount++;
                return true;
            }

            // BUG: "polite" immediate retry — if lock is contested, yield with no delay.
            // Under load all competing nodes hit this branch at the same time, all back
            // off to the top of the loop simultaneously, and re-contend in lock step.
            // No node ever makes progress.
            Thread.yield();
            // No sleep here — zero-delay retry is what creates the livelock.
        }
        return false; // gave up after MAX_RETRIES
    }

    /**
     * Fixed version: introduces a randomised back-off delay before each retry.
     * The asymmetry in wait times breaks the lock-step cycle — whichever node
     * draws the shorter delay will acquire the lock and make progress.
     *
     * @param nodeId the unique identifier of the requesting node
     * @return {@code true} if the lock was acquired and the critical section completed
     */
    public boolean acquireLockFixed(String nodeId) throws InterruptedException {
        long delayMs = 1L;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (lockHolder.compareAndSet(null, nodeId)) {
                performCriticalWork(nodeId);
                lockHolder.set(null);
                acquisitionCount++;
                return true;
            }

            // Fixed: exponential back-off with jitter breaks the symmetry
            Thread.sleep(delayMs + (long)(Math.random() * delayMs));
            delayMs = Math.min(delayMs * 2, 100L); // cap at 100 ms
        }
        return false;
    }

    /**
     * Simulates the work done inside the critical section.
     * Intentionally lightweight so the example runs quickly.
     */
    private void performCriticalWork(String nodeId) {
        // Simulate a small amount of work
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += i;
        }
        // Use sum to prevent dead-code elimination
        if (sum < 0) throw new IllegalStateException("unexpected");
    }

    public int getAcquisitionCount() {
        return acquisitionCount;
    }

    public String getLockHolder() {
        return lockHolder.get();
    }
}
