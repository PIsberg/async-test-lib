package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulates a server that spawns a dedicated thread per incoming connection.
 *
 * <p>BUG: {@link #handleConnection(String)} creates and starts a new thread for
 * every connection. The thread loops indefinitely while {@code running} is
 * {@code true}. {@link #shutdown()} sets {@code running = false} and joins all
 * threads, but it is never called in the concurrent test — so every invocation
 * leaks a live thread into the JVM.
 */
public class ConnectionHandlerService {

    private volatile boolean running = true;
    private final List<Thread> connectionThreads = new ArrayList<>();

    /**
     * Accept a connection and spawn a dedicated handler thread.
     * The thread runs until {@link #shutdown()} is called.
     */
    public void handleConnection(String connectionId) {
        Thread handler = new Thread(() -> {
            while (running) {
                // Simulate processing packets for this connection
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "conn-handler-" + connectionId);
        handler.setDaemon(false); // BUG: non-daemon, keeps JVM alive
        synchronized (connectionThreads) {
            connectionThreads.add(handler);
        }
        handler.start();
    }

    /**
     * Signals all connection threads to stop and waits for them to terminate.
     * Must be called to avoid thread leaks — but is never called in the test.
     */
    public void shutdown() throws InterruptedException {
        running = false;
        synchronized (connectionThreads) {
            for (Thread t : connectionThreads) {
                t.interrupt();
                t.join(200);
            }
            connectionThreads.clear();
        }
    }

    public List<Thread> getConnectionThreads() {
        synchronized (connectionThreads) {
            return List.copyOf(connectionThreads);
        }
    }
}
