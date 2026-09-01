package com.example.agentfixture;

/**
 * Sleeps sub-millisecond inside a {@code synchronized} method, through {@code sleep(long, int)}.
 *
 * <p>Half a millisecond, which is the interesting case rather than a convenient one: the detector
 * counts milliseconds and drops anything at or below zero, so a hook that truncated the nanosecond
 * part would record nothing here and the row would look exactly like an unwoven call site. The
 * hook rounds up instead.
 */
public class SleepingNanosInSynchronizedMethodBean {

    private int processed;

    /**
     * Processes one request. The monitor is this instance, taken by the access flag.
     *
     * @throws InterruptedException if interrupted while sleeping
     */
    public synchronized void process() throws InterruptedException {
        processed++;
        Thread.sleep(0, 500_000);
    }

    /** {@return how many requests were processed} */
    public synchronized int processed() {
        return processed;
    }
}
