package com.example.agentfixture;

/**
 * Signals, then waits in a cross-method predicate loop (#707).
 *
 * <p>The loop is in {@link #signalThenAwait}, while {@code wait} is called from helper method
 * {@link #awaitOnce}. The agent must recognise that the enclosing loop contains a wait and emit
 * {@code loopBackEdge}, keeping this correct code silent.
 */
public class CrossMethodLoopWaitHandOffBean {

    private final Object monitor = new Object();
    private boolean ready;

    /**
     * Signals whoever is waiting, then polls for the predicate until a deadline across methods.
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
                awaitOnce(leftMillis);
            }
        }
    }

    private void awaitOnce(long millis) throws InterruptedException {
        monitor.wait(millis);
    }
}
