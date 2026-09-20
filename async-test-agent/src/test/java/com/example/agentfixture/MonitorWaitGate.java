package com.example.agentfixture;

/**
 * The {@link WaitGate} implementation that actually waits (#709).
 *
 * <p>Never executed. Woven before its caller so that its waiting method is registered under this
 * class's name, which is not the name the caller's call site carries.
 */
public class MonitorWaitGate implements WaitGate {

    private final Object monitor = new Object();

    @Override
    public void awaitSignal(long millis) throws InterruptedException {
        synchronized (monitor) {
            monitor.wait(millis);
        }
    }
}
