package com.example.agentfixture;

/**
 * Signals, then waits behind an {@code if}: the missed-signal bug.
 *
 * <p>The first {@code notifyAll} of a round finds nobody waiting and is discarded, and nothing
 * records that it happened. The last thread to wait in the round therefore waits for a signal that
 * already came and went, and only the timeout lets it out. Nothing here is declared to the library:
 * the agent substitutes the {@code wait} and the {@code notifyAll}, and finds no backward jump
 * around the wait.
 */
public class IfWaitHandOffBean {

    private final Object monitor = new Object();
    private boolean ready;

    /**
     * Signals whoever is waiting, then waits for a signal of its own.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void signalThenAwait() throws InterruptedException {
        synchronized (monitor) {
            monitor.notifyAll();
        }
        synchronized (monitor) {
            if (!ready) {
                monitor.wait(20);
            }
        }
    }
}
