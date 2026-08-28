package com.example.agentfixture;

/**
 * Sleeps inside a {@code synchronized} <em>method</em>, which is the shape #388 was about.
 *
 * <p>{@link SleepingUnderLockBean} uses a {@code synchronized} block, and the field weaver rewrites
 * the {@code MONITORENTER} that block compiles to, so the lockset knows the monitor is held. A
 * synchronized method compiles to no instruction at all - {@code ACC_SYNCHRONIZED} is an access
 * flag and the JVM takes the monitor on entry - so nothing tells the lockset anything, and
 * {@code HeldLocks.topHeld()} answers null inside a method that very much holds a lock.
 *
 * <p>Same bug as the block, and for a long time only one of the two was visible.
 */
public class SleepingInSynchronizedMethodBean {

    private int processed;

    /**
     * Processes one request. The monitor is this instance, taken by the access flag.
     *
     * @throws InterruptedException if interrupted while sleeping
     */
    public synchronized void process() throws InterruptedException {
        processed++;
        Thread.sleep(1);
    }

    /** {@return how many requests were processed} */
    public synchronized int processed() {
        return processed;
    }
}
