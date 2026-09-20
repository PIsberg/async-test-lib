package com.example.agentfixture;

/**
 * A bounded poll whose wait is one method away, in a class of its own (#715).
 *
 * <p>The loop re-tests its predicate around every wait, so a notify it did not hear cannot strand
 * it and it must not be reported. What makes that visible to the weaver is the mark in front of
 * the backward jump, and the mark is only emitted once the weaver knows that
 * {@link StaleWaitHelper#awaitOnce(long)} waits. Woven before that class, it gets no mark; the
 * point of #715 is that load-time weaving delivers exactly that order.
 */
public class StaleCallerSample {

    private final StaleWaitHelper helper = new StaleWaitHelper();
    private boolean ready;

    /**
     * Polls for the predicate until a deadline, waiting through the helper.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void loopThroughHelper() throws InterruptedException {
        synchronized (helper.monitor()) {
            long deadline = System.nanoTime() + 20_000_000L;
            while (!ready) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    break;
                }
                helper.awaitOnce(leftMillis);
            }
        }
    }
}
