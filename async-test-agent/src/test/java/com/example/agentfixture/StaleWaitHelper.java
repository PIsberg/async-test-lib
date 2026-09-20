package com.example.agentfixture;

/**
 * A wait helper used by exactly one test, so that the order it is woven in is that test's to
 * choose (#715).
 *
 * <p>The weaver's index of waiting methods is static and never cleared, which is what lets a
 * helper woven in one test help a caller woven in another. That is the behaviour under test here,
 * so this pair may not be shared: if {@code CrossClassWaitHelperWeavingTest} had already woven
 * {@link WaitHelperBase}, a test that needs the helper to be <em>unknown</em> first would pass or
 * fail on which class surefire happened to run first.
 */
public class StaleWaitHelper {

    private final Object monitor = new Object();

    /** {@return the monitor the caller locks, so the caller's wait is on the same object} */
    public Object monitor() {
        return monitor;
    }

    /**
     * Waits once, with a bound, holding the monitor the caller locked.
     *
     * @param millis how long to wait at most
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitOnce(long millis) throws InterruptedException {
        monitor.wait(millis);
    }
}
