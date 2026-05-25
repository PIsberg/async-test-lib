package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes requests on virtual threads.
 *
 * <p>BUG: {@link #processRequest(String)} acquires a {@code synchronized} lock
 * and calls {@link Thread#sleep(long)} inside it. When a virtual thread is
 * inside a {@code synchronized} block it is pinned to its carrier thread and
 * cannot unmount during blocking operations. Under load this exhausts the
 * carrier thread pool, stalling all other virtual threads.
 */
public class VirtualWorkerService {

    // BUG: synchronized pins the carrier thread during sleep.
    private final Object sharedLock = new Object();
    private final AtomicInteger processedCount = new AtomicInteger(0);

    /**
     * Process a request. The {@code synchronized} block combined with
     * {@code Thread.sleep()} pins the carrier thread for the full sleep duration,
     * preventing other virtual threads from using the carrier.
     */
    public void processRequest(String id) {
        synchronized (sharedLock) {
            // BUG: blocking inside synchronized pins the carrier thread.
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            processedCount.incrementAndGet();
        }
    }

    public int getProcessedCount() {
        return processedCount.get();
    }
}
