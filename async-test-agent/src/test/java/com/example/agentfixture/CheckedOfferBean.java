package com.example.agentfixture;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The twin of {@link DroppingOfferBean}: the same offer to a full queue, with the answer read.
 *
 * <p>The queue still fills and the offer still returns {@code false}, so the rejected count and
 * the saturation line are the same shape as the dropping bean's. What differs is one instruction
 * after the call: {@code IFNE} instead of {@code POP}. That is backpressure, not a bug, and the
 * dropped-element finding must not appear for it.
 *
 * <p>Capacity three rather than two, so a report from this bean's run can be told from the
 * dropping bean's by its saturation line.
 */
public class CheckedOfferBean {

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(3);

    private final AtomicInteger rejected = new AtomicInteger();

    /** Offers and handles the rejection. */
    public void enqueue(String element) {
        if (!queue.offer(element)) {
            rejected.incrementAndGet();
        }
    }

    /** {@return how many offers were rejected and handled} */
    public int rejected() {
        return rejected.get();
    }
}
