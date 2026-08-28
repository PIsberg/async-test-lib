package com.example.agentfixture;

/**
 * The correct twin of {@link SleepingUnderLockBean}: the same sleep, outside the lock.
 *
 * <p>This is the fix, and it must stay silent. The lock covers only the state change and the sleep
 * happens after it is released, so callers throttle themselves without throttling each other. The
 * agent substitutes exactly the same {@code Thread.sleep} call, so what the detector has to
 * distinguish is whether a lock was held at that moment - not whether a sleep happened.
 *
 * <p>A finding here would be a false positive on every ordinary use of {@code Thread.sleep}: rate
 * limiting, back-off and polling are all sleeps outside a lock, and they vastly outnumber the bug.
 */
public class SleepingOutsideLockBean {

    private int processed;

    /**
     * Processes one request, sleeping after releasing the lock.
     *
     * @throws InterruptedException if interrupted while sleeping
     */
    public void process() throws InterruptedException {
        synchronized (this) {
            processed++;
        }
        Thread.sleep(1);
    }

    /** {@return how many requests were processed} */
    public synchronized int processed() {
        return processed;
    }
}
