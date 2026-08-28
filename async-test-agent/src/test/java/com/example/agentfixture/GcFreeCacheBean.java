package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The correct twin of {@link GcForcingCacheBean}: the same eviction, without asking for a
 * collection.
 *
 * <p>This is the fix, and it must stay silent. Dropping the reference is the whole job; when the
 * collector runs is the JVM's decision and not the cache's.
 *
 * <p>It sleeps deliberately. {@code Thread.sleep} is the <em>other</em> entry in the agent's
 * static substitution table, so this bean exercises the static path without touching
 * {@code System.gc()}. A finding here would mean the two static entries are bound to each other's
 * hooks - a mis-binding no test that only calls {@code System.gc()} could ever reveal, because in
 * that direction both tables agree. The sleep is outside any lock, so it is not the sleep bug
 * either.
 */
public class GcFreeCacheBean {

    private final AtomicInteger evictions = new AtomicInteger();

    /**
     * Evicts one entry and lets the collector decide when to run.
     *
     * @throws InterruptedException if interrupted while sleeping
     */
    public void evict() throws InterruptedException {
        evictions.incrementAndGet();
        Thread.sleep(1);
    }

    /** {@return how many entries were evicted} */
    public int evictions() {
        return evictions.get();
    }
}
