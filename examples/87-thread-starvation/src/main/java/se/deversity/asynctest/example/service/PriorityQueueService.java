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

    /**
     * Called with the task id at the moment the task finally holds the shared lock and at the
     * moment it lets go. No-ops by default, so production behaviour is unchanged whether or not
     * a test is watching.
     *
     * <p>Starvation is the gap between "submitted" and "actually running", and that gap opens
     * inside the pool thread, waiting on the monitor. A test can time the submit itself, but it
     * cannot see the other end without this. This is the seam, not the bug.
     */
    private volatile java.util.function.Consumer<String> onTaskRunning = taskId -> { };
    private volatile java.util.function.Consumer<String> onTaskDone = taskId -> { };

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
                onTaskRunning.accept(taskId);
                // Simulates slow high-priority work while holding the lock.
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                processedCount++;
            }
            onTaskDone.accept(taskId);
        });
    }

    /**
     * Submit a low-priority task. Also needs the shared lock but is routinely
     * starved by high-priority tasks that re-acquire it first.
     */
    public Future<?> submitLowPriority(String taskId) {
        return pool.submit(() -> {
            synchronized (sharedLock) {
                onTaskRunning.accept(taskId);
                try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                processedCount++;
            }
            onTaskDone.accept(taskId);
        });
    }

    /**
     * Installs the hooks a demonstration needs to time the wait a task actually suffered.
     *
     * @param running called with the task id on the pool thread, once it holds the shared lock
     * @param done    called with the task id on the pool thread, once it has let the lock go
     */
    public void observeTaskPhases(java.util.function.Consumer<String> running,
                                  java.util.function.Consumer<String> done) {
        this.onTaskRunning = running;
        this.onTaskDone = done;
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
