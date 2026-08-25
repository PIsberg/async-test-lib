package se.deversity.asynctest.example.service;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A task-queue worker that waits for work by spinning instead of blocking.
 *
 * <p>BUG: {@link #awaitTask(long, Runnable, Runnable)} polls an empty queue in a tight loop with
 * no {@code Thread.onSpinWait()}, no {@code yield}, and no park. A worker that loses the race for
 * the next task burns a whole core for the length of its spin budget, and it burns it while other
 * threads have real work to do. Adaptive locks do spin before parking, which is why this shape
 * survives review; the bug is a spin budget large enough to matter with nothing to fall back to.
 *
 * <p>BusyWaitDetector flags a loop once one thread's iteration count passes the spin threshold
 * (10,000) before the loop exits.
 *
 * <p>FIX: block on {@code LinkedBlockingQueue.take()}, which parks the thread at zero CPU cost
 * until an element arrives, or use {@code wait()}/{@code notify()} to the same effect.
 *
 * <p>INSTRUMENTATION: BusyWaitDetector is recording-fed. The two {@code Runnable} hooks on
 * {@code awaitTask} are how a caller tells it where the loop iterated and where it exited; they
 * are plain {@code java.util.function} types, so the production path never imports the test
 * library. This is the seam, not the bug.
 */
public class SpinPollingWorker {

    private final ConcurrentLinkedQueue<String> taskQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    private String lastResult;

    /**
     * Submits a task to the queue for processing.
     *
     * @param task the task payload
     */
    public void submit(String task) {
        taskQueue.offer(task);
    }

    /**
     * Drains every task currently queued and returns the last one.
     *
     * <p>This is the sequential path: it stops as soon as the queue is empty, so it never spins
     * and never reaches the detector's threshold. That is exactly why the sequential tests below
     * are quiet - the bug needs a second thread to be visible.
     *
     * @return the last task polled, or null if the queue was already empty
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
     * Waits for the next task by spinning on the queue.
     *
     * <p>BUG: the loop performs no back-off of any kind. Every iteration is a full poll of a
     * lock-free queue, and a worker that finds nothing keeps the core busy for the whole budget.
     * With more pollers than tasks, the losers spend their entire time slice discovering that
     * there is still nothing to do.
     *
     * @param maxSpins     how many times to poll before giving up
     * @param onIteration  called once per poll (may be null)
     * @param onExit       called once as the loop exits, whether or not a task was found (may be null)
     * @return the task claimed, or null if the budget ran out first
     */
    public String awaitTask(long maxSpins, Runnable onIteration, Runnable onExit) {
        for (long spins = 0; spins < maxSpins && running; spins++) {
            if (onIteration != null) onIteration.run();
            String task = taskQueue.poll();
            if (task != null) {
                lastResult = task;
                if (onExit != null) onExit.run();
                return task;
            }
            // BUG: no Thread.onSpinWait(), no Thread.yield(), no park - just poll again.
        }
        if (onExit != null) onExit.run();
        return null;
    }

    /**
     * {@return the number of tasks still queued}
     */
    public int pending() {
        return taskQueue.size();
    }

    /**
     * {@return the last task this worker claimed, or null}
     */
    public String getLastResult() {
        return lastResult;
    }

    /**
     * Stops the spin loop, so a worker parked in {@link #awaitTask} returns at its next iteration.
     */
    public void stop() {
        running = false;
    }
}
