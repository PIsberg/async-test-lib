package com.example.agentfixture;

/**
 * The interface a caller holds when the waiting implementation is behind virtual dispatch (#709).
 *
 * <p>Nothing here waits, and nothing can: an interface method has no body. A caller typed to this
 * interface writes this name at the call site, so resolving it means looking at what implements
 * it, which is the direction the exact-name lookup cannot go.
 */
public interface WaitGate {

    /**
     * Blocks until signalled or until the bound elapses.
     *
     * @param millis the bound on the wait
     * @throws InterruptedException if interrupted while waiting
     */
    void awaitSignal(long millis) throws InterruptedException;
}
