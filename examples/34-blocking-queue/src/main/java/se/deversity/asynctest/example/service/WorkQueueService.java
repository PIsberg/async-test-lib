package se.deversity.asynctest.example.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * BUGGY service that demonstrates blocking-queue misuse.
 *
 * <p>BUG: offer() returns false and discards the task when the queue is full (capacity = 5),
 * and {@link #submitTask(String)}'s callers routinely ignore that return value. poll() returns
 * null when the queue is empty, and {@link #processNext()} calls toUpperCase() on it without a
 * guard, so an empty queue is a NullPointerException.
 *
 * <p>FIX: Use put() on the producer side (blocks until space is available) and
 * take() or a null-guarded poll(timeout, unit) on the consumer side.
 *
 * <p>A note on what the detector will and will not say about this. A rejected offer() is not
 * itself a finding: {@code if (!q.offer(x)) retryLater(x);} is what correct backpressure looks
 * like, and BlockingQueueDetector counts rejections without treating them as an issue, because
 * treating them as one flagged every correct bounded queue. What it does gate on is saturation,
 * a queue sitting at its bound, which is the shape that says the sizing or the drain rate is
 * wrong. This example produces that, and the rejection count is visible alongside it.
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

    /**
     * {@return the bound this queue was built with}
     */
    public int capacity() {
        return CAPACITY;
    }

    public BlockingQueue<String> getQueue() {
        return queue;
    }
}
