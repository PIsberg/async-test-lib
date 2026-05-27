package se.deversity.asynctest.example.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * BUGGY service that demonstrates blocking-queue misuse.
 *
 * BUG: offer() silently discards tasks when the queue is full (capacity = 5).
 *      poll() returns null when the queue is empty; calling toUpperCase() on
 *      null causes NullPointerException under concurrent load.
 *
 * FIX: Use put() on the producer side (blocks until space is available) and
 *      take() or a null-guarded poll(timeout, unit) on the consumer side.
 */
public class WorkQueueService {

    private static final int CAPACITY = 5;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);

    /** Submit a task. Returns false (and silently drops the task) when full. */
    public boolean submitTask(String task) {
        return queue.offer(task);   // BUG: non-blocking — returns false when full
    }

    /**
     * Process the next task.
     * BUG: poll() returns null when empty; the string operation throws NPE.
     */
    public String processNext() {
        String task = queue.poll();  // BUG: may return null
        return task.toUpperCase();   // BUG: NPE when task is null
    }

    public int pendingCount() {
        return queue.size();
    }

    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    public BlockingQueue<String> getQueue() {
        return queue;
    }
}
