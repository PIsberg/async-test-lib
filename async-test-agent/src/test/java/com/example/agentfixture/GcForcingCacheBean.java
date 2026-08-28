package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks for a full collection after evicting, which is the oldest way to make a fast test slow.
 *
 * <p>The intent is usually to reclaim what was just evicted before measuring anything. The effect
 * is a stop-the-world pause of indeterminate length in the middle of a concurrent run: every
 * latency the run measures is inflated by it, timeouts fire that nothing else would have caused,
 * and the thread interleavings the run exists to explore are rescheduled around the pause.
 *
 * <p>Nothing here is declared to the library and no test calls a {@code record} method. The agent
 * substitutes the {@code System.gc()} call site, which is an {@code invokestatic} and therefore
 * reachable only through the static substitution path.
 */
public class GcForcingCacheBean {

    private final AtomicInteger evictions = new AtomicInteger();

    /** Evicts one entry, then asks the JVM to collect it. */
    public void evict() {
        evictions.incrementAndGet();
        System.gc();
    }

    /** {@return how many entries were evicted} */
    public int evictions() {
        return evictions.get();
    }
}
