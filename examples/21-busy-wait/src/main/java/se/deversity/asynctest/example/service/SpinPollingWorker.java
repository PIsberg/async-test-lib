package se.deversity.asynctest.example.service;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A task-queue worker that polls for work in a tight spin loop.
 *
 * BUG: The worker continuously calls taskQueue.isEmpty() and taskQueue.poll()
 * in a hot loop without ever yielding or parking the thread. This burns 100%
 * of a CPU core for the entire wait duration and starves other threads on
 * the same core from making progress.
 *
 * BusyWaitDetector flags the loop as a busy-wait hotspot when the iteration
 * count exceeds the spin threshold.
 *
 * FIX: Replace the spin loop with a blocking take() on a LinkedBlockingQueue,
 * or use wait()/notify() so the thread is parked at zero CPU cost while idle.
 */
public class SpinPollingWorker {

    private final ConcurrentLinkedQueue<String> taskQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    private String lastResult;

    /**
     * Submits a task to the queue for processing.
     */
    public void submit(String task) {
        taskQueue.offer(task);
    }

    /**
     * Processes pending tasks using a tight spin loop.
     *
     * BUG: This method spins continuously calling isEmpty() and poll()
     * without any yielding, sleeping, or parking. Under concurrent load,
     * every worker thread wastes its entire time slice on polling even when
     * the queue is empty, leaving no CPU for threads doing real work.
     */
    public String process() {
        String result = null;
        while (!taskQueue.isEmpty()) {
            result = taskQueue.poll();
        }
        lastResult = result;
        return result;
    }

    /**
     * Processes tasks using a tight spin loop, invoking {@code loopCallback}
     * on each iteration so callers can instrument the spin (e.g. record to a
     * BusyWaitDetector) without the service importing any test-scoped types.
     *
     * @param loopCallback called once per poll iteration (may be null)
     * @param yieldCallback called once after the loop exits (may be null)
     * @return the last task polled, or null if the queue was empty
     */
    public String processInstrumented(Runnable loopCallback, Runnable yieldCallback) {
        String result = null;

        while (!taskQueue.isEmpty()) {
            result = taskQueue.poll();
            if (loopCallback != null) loopCallback.run();
        }

        if (yieldCallback != null) yieldCallback.run();

        lastResult = result;
        return result;
    }

    public String getLastResult() {
        return lastResult;
    }

    public void stop() {
        running = false;
    }
}
