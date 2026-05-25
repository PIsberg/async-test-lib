package se.deversity.asynctest.example.service;

/**
 * Routes messages between producers and consumers using wait/notify.
 *
 * <p><strong>Bug:</strong> {@link #waitForMessage()} calls {@code lock.wait()} with no
 * timeout. If {@link #deliver(String)} is never called — due to a race or a dropped
 * message — the thread blocks forever, hanging the test or the application.
 *
 * <p><strong>Fix:</strong> Replace {@code lock.wait()} with {@code lock.wait(timeoutMs)}
 * inside a {@code while (!messageAvailable)} guard, and handle the timeout by failing fast.
 */
public class MessageRouter {

    private final Object lock = new Object();
    private volatile String pendingMessage = null;

    /**
     * Blocks until a message is delivered.
     * Bug: no timeout — hangs indefinitely if deliver() is never called.
     */
    public String waitForMessage() throws InterruptedException {
        synchronized (lock) {
            // BUG: if notify is never called, this waits forever
            if (pendingMessage == null) {
                lock.wait();  // no timeout!
            }
            String msg = pendingMessage;
            pendingMessage = null;
            return msg;
        }
    }

    /**
     * Delivers a message and notifies waiting threads.
     */
    public void deliver(String message) {
        synchronized (lock) {
            pendingMessage = message;
            lock.notifyAll();
        }
    }

    /** Returns the internal lock so tests can instrument it. */
    public Object getLock() {
        return lock;
    }
}
