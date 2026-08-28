package com.example.agentfixture;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * The correct twin of {@link LeakyCoordinationBean}: every permit released, every offer checked.
 *
 * <p>The release sits in a {@code finally}, which is the fix, and the queue is unbounded so an
 * offer cannot fail. Both use exactly the same woven call sites as the leaky bean, so what the
 * detectors must distinguish is the protocol, not the instruction. A finding here would be a false
 * positive on correctly written coordination, which is most coordination.
 */
public class CorrectCoordinationBean {

    private final Semaphore permits = new Semaphore(4);
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    /** Takes a permit and always returns it. */
    public void useAPermit() throws InterruptedException {
        permits.acquire();
        try {
            // the work the permit guards
            queue.size();
        } finally {
            permits.release();
        }
    }

    /** Offers to an unbounded queue, where the answer is always true. */
    public void enqueue(String element) {
        queue.offer(element);
    }
}
