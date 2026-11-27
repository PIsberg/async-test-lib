package com.example.agentfixture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Timed latch awaits in the two shapes #1501 separates: a stop-latch poll, where the boolean is
 * the loop condition and a timeout is handled, and an await whose boolean is thrown away, where
 * the caller goes on as though the latch had reached zero.
 */
public class StopLatchBean {

    private final CountDownLatch stop = new CountDownLatch(1);

    /** Polls the stop latch three times; nothing stops it, and every timeout is read. */
    public int pollUntilStopped() throws InterruptedException {
        int polls = 0;
        while (!stop.await(1, TimeUnit.MILLISECONDS) && ++polls < 3) {
            Thread.onSpinWait();
        }
        return polls;
    }

    /** Waits for a latch nothing counts down, and ignores that the wait timed out. */
    public void awaitIgnoringTheTimeout() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        done.await(1, TimeUnit.MILLISECONDS);
    }
}
