package com.example.agentfixture;

import java.util.concurrent.CompletableFuture;

/**
 * A root {@link CompletableFuture} dropped, kept, and an unrelated static call whose result is
 * dropped, for the static-call discard lookahead.
 */
public class AsyncRootShapesSample {

    /**
     * Starts {@code task} and drops the future: the static call is followed by a {@code POP}.
     *
     * @param task what to run
     */
    public void discarded(Runnable task) {
        CompletableFuture.runAsync(task);
    }

    /**
     * Starts {@code task} and keeps the future.
     *
     * @param task what to run
     * @return the future
     */
    public CompletableFuture<Void> kept(Runnable task) {
        return CompletableFuture.runAsync(task);
    }

    /** Drops the result of a static call the table does not list. */
    public void unrelatedStaticPopped() {
        String.valueOf(42);
    }
}
