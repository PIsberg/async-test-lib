package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * BUGGY service that demonstrates sleep-in-lock anti-pattern.
 *
 * BUG: processRequest() holds the intrinsic lock on {@code this} while calling
 *      Thread.sleep(50) for "rate limiting". Every concurrent caller must wait
 *      in the synchronized queue for the full 50 ms sleep, collapsing throughput
 *      to a single request per 50 ms regardless of how many threads are used.
 *
 * FIX: Move the sleep outside the synchronized block, or use a
 *      ScheduledExecutorService / RateLimiter to throttle without lock holding.
 */
public class ThrottledService {

    private final List<String> processed = new ArrayList<>();
    private int requestCount = 0;

    /**
     * Rate-limited request processor.
     * BUG: sleeps inside the synchronized block, blocking all concurrent callers.
     */
    public synchronized void processRequest(String id) {
        requestCount++;
        processed.add(id);

        // BUG: "rate limiting" implemented as a sleep while holding the lock.
        // Every caller queues behind this sleep, killing throughput.
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized int getRequestCount() {
        return requestCount;
    }

    public synchronized List<String> getProcessed() {
        return new ArrayList<>(processed);
    }
}
