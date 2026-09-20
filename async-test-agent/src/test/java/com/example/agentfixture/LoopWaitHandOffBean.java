package com.example.agentfixture;

/**
 * The twin of {@link IfWaitHandOffBean}: the same signal and the same timed wait, inside the loop
 * that re-tests the predicate.
 *
 * <p>A notify that found nobody waiting cannot strand this wait, because the loop reads the state
 * the notify announced rather than relying on having heard it. The wait still runs out after a
 * lost notify, exactly as its twin's does; what differs is the backward jump around it, which the
 * agent marks.
 */
public class LoopWaitHandOffBean {

    private final Object monitor = new Object();
    private boolean ready;

    /**
     * Signals whoever is waiting, then polls for the predicate until a deadline.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void signalThenAwait() throws InterruptedException {
        synchronized (monitor) {
            monitor.notifyAll();
        }
        synchronized (monitor) {
            long deadline = System.nanoTime() + 20_000_000L;
            while (!ready) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    break;
                }
                monitor.wait(leftMillis);
            }
        }
    }
}
