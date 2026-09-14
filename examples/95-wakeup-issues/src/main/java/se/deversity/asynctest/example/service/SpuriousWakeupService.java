package se.deversity.asynctest.example.service;

/**
 * Synchronizes threads using a ready flag and wait/notify.
 *
 * <p><strong>Bug:</strong> {@link #waitUntilReady()} guards {@code monitor.wait()} with
 * an {@code if} statement instead of a {@code while} loop. The JVM specification allows
 * {@code wait()} to return spuriously — without a {@code notify()} — meaning the thread
 * may continue executing even when {@code ready} is still {@code false}. The wait is timed
 * so a caller never hangs, and a timed-out return reaches the {@code if} guard exactly as a
 * spurious one does.
 *
 * <p><strong>Fix:</strong> Replace {@code if (!ready) monitor.wait(...)} with
 * {@code while (!ready) { monitor.wait(...); }} to re-check the condition after every wakeup.
 */
public class SpuriousWakeupService {

    /** How long one wait may last before it returns on its own. */
    static final long WAIT_SLICE_MS = 20;

    private final Object monitor = new Object();
    private volatile boolean ready = false;

    /**
     * Waits until {@link #setReady()} is called.
     * Bug: uses if instead of while, so a return without a notify proceeds unready.
     */
    public void waitUntilReady() throws InterruptedException {
        synchronized (monitor) {
            // BUG: should be while (!ready) to guard against spurious wakeups
            if (!ready) {
                monitor.wait(WAIT_SLICE_MS);
            }
        }
    }

    /**
     * Sets the ready flag and wakes all waiting threads.
     */
    public void setReady() {
        synchronized (monitor) {
            ready = true;
            monitor.notifyAll();
        }
    }

    /** Returns the ready state. */
    public boolean isReady() {
        return ready;
    }

    /** Returns the internal monitor so tests can instrument it. */
    public Object getMonitor() {
        return monitor;
    }

    /** Resets state for the next test round. */
    public void reset() {
        synchronized (monitor) {
            ready = false;
        }
    }
}
