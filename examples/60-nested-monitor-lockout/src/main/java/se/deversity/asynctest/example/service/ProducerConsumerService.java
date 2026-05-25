package se.deversity.asynctest.example.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A producer-consumer service with a nested monitor lockout bug.
 *
 * BUG: {@link #produce(String)} holds {@code lockA} and calls {@code lockB.wait()}.
 * {@link #consume()} holds {@code lockB} and calls {@code lockA.notifyAll()}.
 * The circular monitor dependency causes both threads to wait indefinitely.
 */
public class ProducerConsumerService {

    // Exposed for the detector API calls in the test
    public final Object lockA = new Object();
    public final Object lockB = new Object();

    private final Deque<String> queue = new ArrayDeque<>();

    /**
     * Adds an item to the queue and waits for a consumer to acknowledge.
     *
     * BUG: holds lockA while waiting on lockB — deadlock if consumer holds lockB.
     */
    public void produce(String item) throws InterruptedException {
        synchronized (lockA) {
            queue.addLast(item);
            // BUG: waiting on lockB while lockA is held
            synchronized (lockB) {
                lockB.wait(50); // timeout to avoid truly hanging the demo
            }
        }
    }

    /**
     * Removes and returns the next item, then notifies the producer via lockA.
     *
     * BUG: holds lockB while calling notifyAll on lockA — deadlock if producer holds lockA.
     */
    public String consume() {
        synchronized (lockB) {
            // BUG: notifying lockA while lockB is held
            synchronized (lockA) {
                lockA.notifyAll();
                return queue.isEmpty() ? null : queue.removeFirst();
            }
        }
    }

    public int queueSize() {
        return queue.size();
    }
}
