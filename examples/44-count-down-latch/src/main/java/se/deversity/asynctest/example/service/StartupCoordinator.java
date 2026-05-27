package se.deversity.asynctest.example.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates a multi-step startup sequence using a CountDownLatch.
 *
 * <p><strong>Bug:</strong> {@code initialize(true)} skips the {@code countDown()} call,
 * so the latch never reaches zero when quick mode is used. Any thread calling
 * {@code waitForStartup()} then blocks indefinitely.
 *
 * <p><strong>Fix:</strong> Always call {@code latch.countDown()} in every branch of
 * {@code initialize()}, regardless of {@code quickMode}.
 */
public class StartupCoordinator {

    private final CountDownLatch latch = new CountDownLatch(3);

    /**
     * Performs one initialization step. Only decrements the latch when
     * {@code quickMode} is {@code false} — the bug: quick-mode paths never count down.
     */
    public void initialize(boolean quickMode) {
        if (!quickMode) {
            // Simulate normal initialization work
            doWork();
            latch.countDown(); // BUG: skipped entirely when quickMode == true
        }
        // quick mode exits without calling countDown()
    }

    /**
     * Blocks until all three initialization steps have called {@code countDown()},
     * or until the timeout elapses.
     *
     * @return {@code true} if startup completed within 2 seconds
     */
    public boolean waitForStartup() throws InterruptedException {
        return latch.await(2, TimeUnit.SECONDS);
    }

    /** Returns the current latch count (number of remaining steps). */
    public long getRemainingCount() {
        return latch.getCount();
    }

    /** Returns the underlying latch for instrumentation in tests. */
    public CountDownLatch getLatch() {
        return latch;
    }

    private void doWork() {
        // Simulate initialization work
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += i;
        }
        // prevent dead-code elimination
        if (sum < 0) throw new IllegalStateException("unreachable");
    }
}
