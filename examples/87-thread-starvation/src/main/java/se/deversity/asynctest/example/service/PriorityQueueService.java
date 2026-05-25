package se.deversity.asynctest.example.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A service that accepts high-priority and low-priority tasks on a shared pool.
 *
 * <p>BUG: both task types contend on the same non-fair {@code synchronized}
 * monitor. High-priority tasks sleep while holding the lock, blocking every
 * other thread. Because intrinsic monitors are not fair, high-priority threads
 * can continuously re-acquire the lock, starving low-priority threads.
 */
public class PriorityQueueService {

    private final ExecutorService pool;
    // Non-fair shared lock — high-priority holders starve low-priority waiters.
    private final Object sharedLock = new Object();
    private int processedCount = 0;

    public PriorityQueueService() {
        this.pool = Executors.newFixedThreadPool(4);
    }

    /**
     * Submit a high-priority task. Holds the shared lock while sleeping,
     * blocking all low-priority threads for the full duration.
     */
    public Future<?> submitHighPriority(String taskId) {
        return pool.submit(() -> {
            synchronized (sharedLock) {
                // Simulates slow high-priority work while holding the lock.
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                processedCount++;
            }
        });
    }

    /**
     * Submit a low-priority task. Also needs the shared lock but is routinely
     * starved by high-priority tasks that re-acquire it first.
     */
    public Future<?> submitLowPriority(String taskId) {
        return pool.submit(() -> {
            synchronized (sharedLock) {
                try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                processedCount++;
            }
        });
    }

    public ExecutorService getPool() { return pool; }

    public int getProcessedCount() {
        synchronized (sharedLock) { return processedCount; }
    }

    public void shutdown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(1, TimeUnit.SECONDS);
    }
}
