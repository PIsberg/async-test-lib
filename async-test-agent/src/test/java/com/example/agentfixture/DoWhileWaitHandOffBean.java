package com.example.agentfixture;

/**
 * The twin of {@link LoopWaitHandOffBean}: the same signal, the same bounded poll and the same
 * deadline guard, with {@code do}/{@code while} in place of {@code while} (#707).
 *
 * <p>Only the order of the wait and the predicate test differs. This shape enters {@code wait}
 * before it has ever read {@code ready}, so a notify that found nobody waiting strands the first
 * wait of the round until its timeout, which is the missed-signal bug. Its bytecode still has a
 * backward jump over the wait, and a deadline {@code if} in front of it, so neither the back-edge
 * alone nor a conditional jump in front of the wait alone tells the two apart: the back-edge here
 * is the predicate test itself, a conditional jump, where the loop twin's is an unconditional
 * {@code goto}.
 */
public class DoWhileWaitHandOffBean {

    private final Object monitor = new Object();
    private boolean ready;

    /**
     * Signals whoever is waiting, then waits before polling for the predicate until a deadline.
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
