package com.github.asynctest;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class AsyncAssert {

    /**
     * Polls the condition until it returns true. Throws AssertionError if the timeout is reached.
     */
    public static void awaitUntil(Callable<Boolean> condition, Duration timeout) {
        awaitUntil(condition, timeout, Duration.ofMillis(10));
    }

    public static void awaitUntil(Callable<Boolean> condition, Duration timeout, Duration pollInterval) {
        long start = System.currentTimeMillis();
        long max = start + timeout.toMillis();

        while (System.currentTimeMillis() < max) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
            } catch (Exception e) {
                // Ignore exceptions during polling, just keep trying
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Polling interrupted");
            }
        }
        
        throw new AssertionError("Condition not met within " + timeout.toMillis() + " ms");
    }

    /**
     * Captures the result or exception of a CompletableFuture non-blockingly, 
     * making it available for later assertions without blocking the current thread.
     */
    public static <T> FutureCapture<T> capture(CompletableFuture<T> future) {
        return new FutureCapture<>(future);
    }
    
    public static class FutureCapture<T> {
        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private volatile boolean complete = false;

        public FutureCapture(CompletableFuture<T> future) {
            future.whenComplete((res, err) -> {
                result.set(res);
                error.set(err);
                complete = true;
            });
        }

        public void awaitDone(Duration timeout) {
            awaitUntil(() -> complete, timeout);
        }

        public T getResult() { return result.get(); }
        public Throwable getError() { return error.get(); }
        public boolean isComplete() { return complete; }
    }
}
