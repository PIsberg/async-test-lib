package com.example.agentfixture;

/**
 * A wait helper declared on a supertype, for the cross-class resolution gate (#709).
 *
 * <p>Never executed. {@code CrossClassWaitHelperWeavingTest} weaves this class so that its waiting
 * method is registered, then weaves a caller that reaches the method through
 * {@link WaitHelperSubclass}. The call site names the subclass, so resolving it needs the type
 * hierarchy and not just the name written at the call.
 */
public class WaitHelperBase {

    private final Object monitor = new Object();

    /** {@return the monitor a caller holds around {@link #awaitOnce}} */
    public Object monitor() {
        return monitor;
    }

    /**
     * Waits once, with the monitor already held by the caller.
     *
     * @param millis the bound on the wait
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitOnce(long millis) throws InterruptedException {
        monitor.wait(millis);
    }
}
