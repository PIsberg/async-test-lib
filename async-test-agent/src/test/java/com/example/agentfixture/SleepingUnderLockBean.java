package com.example.agentfixture;

/**
 * Sleeps while holding the monitor, which is the rate-limiter that throttles everybody.
 *
 * <p>The intent is usually rate limiting, and the effect is that every concurrent caller queues
 * behind the sleep for its full duration: throughput collapses to one call per sleep however many
 * threads are used. Nothing here is declared to the library and no test calls a {@code record}
 * method. The agent weaves the {@code MONITORENTER} that takes the lock and substitutes the
 * {@code Thread.sleep} that holds it, and the lockset already in place answers whether the two
 * overlapped.
 */
public class SleepingUnderLockBean {

    private int processed;

    /**
     * Processes one request, sleeping inside the lock.
     *
     * @throws InterruptedException if interrupted while sleeping
     */
    public void process() throws InterruptedException {
        synchronized (this) {
            processed++;
            Thread.sleep(1);
        }
    }

    /** {@return how many requests were processed} */
    public synchronized int processed() {
        return processed;
    }
}
