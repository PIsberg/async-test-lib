package com.example.agentfixture;

/**
 * The twin of {@link LoopWaitHandOffBean}: the same signal and the same bounded poll, with
 * {@code do}/{@code while} in place of {@code while} (#707).
 *
 * <p>Only the order of the wait and the predicate test differs. This shape enters {@code wait}
 * before it has ever read {@code ready}, so a notify that found nobody waiting strands the first
 * wait of the round until its timeout, which is the missed-signal bug. Its bytecode still has a
 * backward jump over the wait, and still reads something before it blocks, so neither the
 * back-edge alone nor a conditional jump in front of the wait alone tells the two apart: the
 * back-edge here is the predicate test itself, a conditional jump, where the loop twin's is an
 * unconditional {@code goto}.
 *
 * <p>The deadline clamps the wait and ends the loop, where the twin's {@code if} guards the wait
 * instead. This bean is the one that must produce a finding, so it has to reach {@code wait} on
 * every run, and a guard does not: a thread descheduled between reading the clock and testing it
 * breaks out having waited for nothing, which is a MUST_FIRE row with no wait to report. That is
 * what it did on all three CI JDKs while passing locally. The guarded form of the shape is pinned
 * where timing cannot reach it, in {@code WaitLoopShapesSample.doWhileLoop}.
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
                monitor.wait(leftMillis > 0L ? leftMillis : 1L);
            } while (!ready && System.nanoTime() < deadline);
        }
    }
}
