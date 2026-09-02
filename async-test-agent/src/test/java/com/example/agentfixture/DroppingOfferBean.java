package com.example.agentfixture;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Offers to a queue of two and never looks at the answer.
 *
 * <p>The bug as production code writes it: {@code queue.offer(element);} as a statement. Once the
 * queue is full every further element is rejected and nothing in the program knows, because the
 * {@code false} that said so was popped off the stack before anyone could read it (#454).
 */
public class DroppingOfferBean {

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);

    /** Offers and discards the boolean. */
    public void enqueue(String element) {
        queue.offer(element);
    }
}
