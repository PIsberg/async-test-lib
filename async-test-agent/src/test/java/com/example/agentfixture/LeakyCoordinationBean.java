package com.example.agentfixture;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Misuses two coordination primitives the way production code does.
 *
 * <p>The semaphore leaks a permit on one path: the caller takes one and returns early without
 * releasing, so the pool drains. The queue is bounded and offered to without checking the
 * result, so once it is full every further element is silently dropped.
 *
 * <p>Neither is declared to the library and no test calls a {@code record} method. These are
 * plumbing - a test author does not think to instrument a semaphore three layers down - which is
 * exactly why the detectors for them were unreachable in practice.
 */
public class LeakyCoordinationBean {

    // Deliberately far more permits than the run consumes. The leak is the finding; exhausting
    // the semaphore would merely hang the test body, and a fixture that proves a leak by
    // deadlocking proves it in the least useful way available.
    private final Semaphore permits = new Semaphore(1000);
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);

    /**
     * Takes a permit and returns it only on the happy path.
     *
     * @param leak whether to take the early return that forgets the release
     */
    public void useAPermit(boolean leak) throws InterruptedException {
        permits.acquire();
        if (leak) {
            return;
        }
        permits.release();
    }

    /** Offers to a queue of capacity two and never looks at the answer. */
    public void enqueue(String element) {
        queue.offer(element);
    }
}
