package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performs background work on a dedicated thread.
 *
 * <p><strong>Bug:</strong> The thread is created without {@code setDaemon(true)}.
 * As a user thread it keeps the JVM alive after the main logic finishes, delaying
 * or preventing shutdown.
 *
 * <p><strong>Fix:</strong> Call {@code thread.setDaemon(true)} before
 * {@code thread.start()}, or use a daemon-configured {@link java.util.concurrent.ThreadFactory}.
 */
public class BackgroundWorker {

    private final AtomicInteger taskCount = new AtomicInteger();

    /**
     * Starts a background thread to run {@code work}. The thread is NOT a daemon
     * thread — the bug that prevents JVM shutdown.
     *
     * @param label a descriptive name for the thread
     * @param work  the runnable to execute in the background
     * @return the started thread (for test inspection)
     */
    public Thread start(String label, Runnable work) {
        Thread thread = new Thread(() -> {
            taskCount.incrementAndGet();
            work.run();
        }, "background-worker-" + label);
        // BUG: thread.setDaemon(true) is missing — this is a user thread
        thread.start();
        return thread;
    }

    /** Returns the total number of background tasks started so far. */
    public int getTaskCount() {
        return taskCount.get();
    }
}
