package se.deversity.asynctest.example.service;

import java.util.LinkedList;

/**
 * A simple task queue that uses wait/notify to coordinate producers and consumers.
 *
 * BUG: {@link #add(String)} calls {@code queue.notify()} without holding the
 * monitor on {@code queue}. This throws {@link IllegalMonitorStateException} at
 * runtime and is detected statically by {@code NotifyWithoutMonitorDetector}.
 */
public class TaskQueue {

    // Exposed for the detector API call in the test
    final LinkedList<String> queue = new LinkedList<>();

    /**
     * Adds a task and notifies waiting consumers.
     *
     * BUG: notify() is called outside a synchronized(queue) block.
     */
    public void add(String task) {
        queue.addLast(task);
        // BUG: not inside synchronized(queue) — throws IllegalMonitorStateException
        try {
            queue.notify();
        } catch (IllegalMonitorStateException e) {
            // In real code this exception is often swallowed, hiding the bug
        }
    }

    /**
     * Blocks until a task is available, then removes and returns it.
     * This method correctly holds the monitor before calling wait().
     */
    public String take() throws InterruptedException {
        synchronized (queue) {
            while (queue.isEmpty()) {
                queue.wait();
            }
            return queue.removeFirst();
        }
    }

    public int size() {
        return queue.size();
    }
}
