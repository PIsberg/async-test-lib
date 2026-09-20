package com.example.agentfixture;

/**
 * Signals, then waits in a do-while loop before testing the predicate: the missed-signal bug (#707).
 *
 * <p>Unlike {@link LoopWaitHandOffBean}, this shape enters {@code wait} before testing {@code !ready}.
 * A notify that found nobody waiting is lost, and the first wait in a round blocks until timeout.
 * The weaver must distinguish this from a true predicate loop and must not emit {@code loopBackEdge}.
 */
public class DoWhileWaitHandOffBean {

    private final Object monitor = new Object();
    private boolean ready;

    /**
     * Signals whoever is waiting, then waits in a do-while loop.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void signalThenAwait() throws InterruptedException {
        synchronized (monitor) {
            monitor.notifyAll();
        }
        synchronized (monitor) {
            long deadline = System.nanoTime() + 20_000_000L;
            do {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    break;
                }
                monitor.wait(leftMillis);
            } while (!ready);
        }
    }
}
