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

    private volatile Runnable onCountDown = () -> { };

    private volatile Runnable onAwaitSuccess = () -> { };

    private volatile Runnable onAwaitTimeout = () -> { };

    /**
     * Performs one initialization step. Only decrements the latch when
     * {@code quickMode} is {@code false} — the bug: quick-mode paths never count down.
     */
    public void initialize(boolean quickMode) {
        if (!quickMode) {
            // Simulate normal initialization work
            doWork();
            latch.countDown(); // BUG: skipped entirely when quickMode == true
            onCountDown.run();
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
        return waitForStartup(2, TimeUnit.SECONDS);
    }

    /**
     * Blocks for at most the given time, and reports which way it went.
     *
     * <p>The timeout is the whole point. CountDownLatchDetector does not treat a latch that
     * never reached zero as a finding on its own, and it is right not to: a latch mid-flight
     * looks exactly like that. What it reports is a wait that gave up, which is the moment the
     * missing countDown() becomes somebody's problem.
     *
     * @param timeout how long to wait
     * @param unit    the unit of {@code timeout}
     * @return true if startup completed inside the timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean waitForStartup(long timeout, TimeUnit unit) throws InterruptedException {
        boolean ready = latch.await(timeout, unit);
        if (ready) {
            onAwaitSuccess.run();
        } else {
            onAwaitTimeout.run();
        }
        return ready;
    }

    /**
     * Installs the hooks CountDownLatchDetector needs. No-ops by default, so production
     * behaviour is unchanged whether or not a test is watching.
     *
     * @param countDown    called after each countDown()
     * @param awaitSuccess called when a wait completed
     * @param awaitTimeout called when a wait gave up
     */
    public void observeLatch(Runnable countDown, Runnable awaitSuccess, Runnable awaitTimeout) {
        this.onCountDown = countDown;
        this.onAwaitSuccess = awaitSuccess;
        this.onAwaitTimeout = awaitTimeout;
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
