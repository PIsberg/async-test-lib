package se.deversity.asynctest.example.service;

import java.util.concurrent.Semaphore;

/**
 * BUGGY rate limiter that demonstrates Semaphore permit leak.
 *
 * BUG: executeRequest() calls sem.acquire() then r.run() then sem.release()
 *      in sequence with no try/finally. If r.run() throws a RuntimeException
 *      the call to sem.release() is skipped. After 5 such failures all permits
 *      are drained and every subsequent caller blocks indefinitely.
 *
 * FIX: wrap the task execution in try/finally:
 *      sem.acquire();
 *      try { r.run(); } finally { sem.release(); }
 */
public class RateLimiter {

    private final Semaphore sem;
    private final int maxConcurrent;

    public RateLimiter(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
        this.sem = new Semaphore(maxConcurrent);
    }

    /**
     * Execute the given request within the concurrency limit.
     * BUG: release() is not in a finally block — exceptions leak permits.
     */
    public void executeRequest(Runnable r) throws InterruptedException {
        sem.acquire();           // consumes a permit
        r.run();                 // BUG: if this throws, release() below is skipped
        sem.release();           // BUG: unreachable on exception path
    }

    public Semaphore getSemaphore() {
        return sem;
    }

    public int availablePermits() {
        return sem.availablePermits();
    }
}
