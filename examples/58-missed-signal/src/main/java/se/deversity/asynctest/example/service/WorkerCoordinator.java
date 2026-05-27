package se.deversity.asynctest.example.service;

/**
 * A coordinator that uses raw {@code wait()} / {@code notify()} to synchronize
 * a signaller thread with a worker thread.
 *
 * BUG: There is no shared flag to record that a signal was sent. If
 * {@link #signal()} is called before the worker has entered {@link #waitForSignal()},
 * the {@code notify()} is lost and the worker waits forever.
 */
public class WorkerCoordinator {

    final Object monitor = new Object();

    /**
     * Sends a signal to the waiting worker.
     *
     * BUG: if the worker has not yet called {@link #waitForSignal()}, this
     * notify() is silently dropped.
     */
    public void signal() {
        synchronized (monitor) {
            monitor.notify();
        }
    }

    /**
     * Blocks until a signal is received.
     *
     * BUG: no boolean flag checked in a loop — a missed signal causes
     * infinite blocking.
     */
    public void waitForSignal() throws InterruptedException {
        synchronized (monitor) {
            monitor.wait(); // no guard condition — missed signals block forever
        }
    }

    /**
     * Signals all waiting workers.
     */
    public void signalAll() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }
}
