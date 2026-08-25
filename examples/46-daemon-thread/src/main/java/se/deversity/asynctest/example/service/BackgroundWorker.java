package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performs background work on a dedicated thread.
 *
 * <p><strong>Bug:</strong> the threads are created without {@code setDaemon(true)}.
 * A non-daemon thread is part of the JVM's keep-alive set, so as long as one is running
 * the process will not exit. A short task gets away with it; a poller does not, and a
 * poller is what background workers usually are.
 *
 * <p><strong>Fix:</strong> Call {@code thread.setDaemon(true)} before
 * {@code thread.start()}, or use a daemon-configured {@link java.util.concurrent.ThreadFactory}.
 */
public class BackgroundWorker {

    /**
     * A hard stop for {@link #startPoller(String)}, so that a forgotten {@link #shutdown()}
     * cannot leave a build hanging on the very bug this class demonstrates.
     */
    private static final long MAX_POLL_MILLIS = 30_000L;

    private final AtomicInteger taskCount = new AtomicInteger();

    private volatile boolean running = true;

    /**
     * Starts a background thread to run {@code work}. The thread is NOT a daemon
     * thread, which is the bug that prevents JVM shutdown.
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
        // BUG: thread.setDaemon(true) is missing, so this is a user thread
        thread.start();
        return thread;
    }

    /**
     * Starts a background poller that keeps running until {@link #shutdown()} is called.
     *
     * <p>This is the shape that makes the missing daemon flag matter. DaemonThreadHygieneDetector
     * only reports a non-daemon thread that is <em>still alive</em> when the run is analysed, and
     * it is right to: a thread that has already terminated cannot hold the JVM open, so flagging
     * it would be flagging nothing. A task that finishes in microseconds is therefore invisible
     * to the detector however many times it is started, which is exactly what this example used
     * to demonstrate.
     *
     * @param label a descriptive name for the thread
     * @return the started thread
     */
    public Thread startPoller(String label) {
        Thread thread = new Thread(() -> {
            taskCount.incrementAndGet();
            long deadline = System.currentTimeMillis() + MAX_POLL_MILLIS;
            while (running && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(5);      // stand-in for waiting on a queue or a socket
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "background-poller-" + label);
        // BUG: thread.setDaemon(true) is missing here too
        thread.start();
        return thread;
    }

    /** Asks every running poller to stop at its next check. */
    public void shutdown() {
        running = false;
    }

    /**
     * {@return the total number of background tasks started so far}
     */
    public int getTaskCount() {
        return taskCount.get();
    }
}
