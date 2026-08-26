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
     * Called from inside the synchronized block, immediately before the sleep, on the thread
     * that holds the monitor. A no-op by default, so production behaviour is unchanged whether
     * or not a test is watching.
     *
     * <p>SleepInLockDetector answers "does the thread calling recordSleep hold a lock", by
     * asking the JVM rather than by reading a stack trace. Recording from the test body, which
     * is where the demonstration used to do it, asks that question outside the synchronized
     * method and always gets no. This is the seam, not the bug.
     */
    private volatile Runnable onSleepWhileHoldingTheLock = () -> { };

    /**
     * Rate-limited request processor.
     * BUG: sleeps inside the synchronized block, blocking all concurrent callers.
     */
    public synchronized void processRequest(String id) {
        requestCount++;
        processed.add(id);

        // BUG: "rate limiting" implemented as a sleep while holding the lock.
        // Every caller queues behind this sleep, killing throughput.
        onSleepWhileHoldingTheLock.run();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Installs the hook a demonstration needs to record the sleep from inside the lock.
     *
     * @param onSleep run on the calling thread while it holds this object's monitor
     */
    public void observeSleepInLock(Runnable onSleep) {
        this.onSleepWhileHoldingTheLock = onSleep;
    }

    public synchronized int getRequestCount() {
        return requestCount;
    }

    public synchronized List<String> getProcessed() {
        return new ArrayList<>(processed);
    }
}
