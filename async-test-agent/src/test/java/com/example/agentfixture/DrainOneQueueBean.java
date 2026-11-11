package com.example.agentfixture;

import java.util.Queue;

/**
 * Takes one job from a thread-safe {@link Queue}, the way it is often written: {@code isEmpty},
 * then {@code remove}.
 *
 * <p>Each call is atomic and the pair is not: two callers that both see the queue non-empty both
 * remove, so on the last job one throws {@code NoSuchElementException}, and with jobs to spare
 * the second takes a job its check never saw (#1249). {@link #drainOneUnderTheQueuesMonitor} is the
 * same pair inside {@code synchronized (queue)}, which makes it atomic among callers that do the
 * same.
 */
public class DrainOneQueueBean {

    private final Queue<Object> queue;

    /**
     * Drains {@code queue}, filled with {@code jobs} jobs; its type names the queue in a report,
     * which tells two beans apart.
     *
     * @param queue the queue to drain
     * @param jobs  how many jobs to put in it first
     */
    public DrainOneQueueBean(Queue<Object> queue, int jobs) {
        this.queue = queue;
        for (int i = 0; i < jobs; i++) {
            queue.add("job-" + i);
        }
    }

    /**
     * Checks for a job, runs {@code afterCheck}, and removes one if the check found any.
     *
     * @param afterCheck run between the check and the remove, which is where a racing caller lands
     */
    public void drainOne(Runnable afterCheck) {
        boolean any = !queue.isEmpty();
        afterCheck.run();
        if (any) {
            queue.remove();
        }
    }

    /** The same check and remove, both under the queue's own monitor. */
    public void drainOneUnderTheQueuesMonitor() {
        synchronized (queue) {
            if (!queue.isEmpty()) {
                queue.remove();
            }
        }
    }
}
