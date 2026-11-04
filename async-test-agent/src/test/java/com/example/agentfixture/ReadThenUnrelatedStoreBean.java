package com.example.agentfixture;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Correct code that the woven atomic path used to flag (#1187): a gauge is read for a log line,
 * then stored from a size that has nothing to do with the read. No update is lost, because the
 * stored value is not built from the value that was read.
 */
public class ReadThenUnrelatedStoreBean {

    private final AtomicInteger gauge = new AtomicInteger();
    private final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
    private volatile int logged;

    public void sample() {
        int seen = gauge.get();
        logged = seen;
        gauge.set(queue.size());
    }
}
