package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link GcForcingCacheBean} with the collection spelled {@code Runtime.getRuntime().gc()}: the same
 * full stop-the-world request, through an {@code invokevirtual} on a receiver rather than the
 * {@code invokestatic} of {@code System.gc()} (#1179).
 */
public class RuntimeGcForcingCacheBean {

    private final AtomicInteger evictions = new AtomicInteger();

    /** Evicts one entry, then asks the runtime to collect it. */
    public void evict() {
        evictions.incrementAndGet();
        Runtime.getRuntime().gc();
    }
}
