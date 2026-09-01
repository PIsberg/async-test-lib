package com.example.agentfixture;

import java.time.Duration;

/**
 * Sleeps a {@link Duration} inside a {@code synchronized} method.
 *
 * <p>The same bug {@link SleepingInSynchronizedMethodBean} carries, spelled the way code written
 * since JDK 19 spells it. Until #440 the weaver's static table named only {@code sleep(long)}, so
 * this shape produced no finding and no log line saying why - the silence an unwoven call site
 * always produces.
 *
 * <p>A separate class from the millisecond bean on purpose: the finding names the monitor as
 * {@code getClass().getName() + "@" + identityHashCode}, so two beans are what let one test
 * assert each overload separately rather than accept either one for both.
 */
public class SleepingDurationInSynchronizedMethodBean {

    private int processed;

    /**
     * Processes one request. The monitor is this instance, taken by the access flag.
     *
     * @throws InterruptedException if interrupted while sleeping
     */
    public synchronized void process() throws InterruptedException {
        processed++;
        Thread.sleep(Duration.ofMillis(1));
    }

    /** {@return how many requests were processed} */
    public synchronized int processed() {
        return processed;
    }
}
