package com.github.asynctest;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class AsyncAssert {

    /**
     * Polls the condition until it returns true. Throws AssertionError if the timeout is reached.
     */
    public static void awaitUntil(Callable<Boolean> condition, Duration timeout) {
        awaitUntil(condition, timeout, Duration.ofMillis(10));
    }

    public static void awaitUntil(Callable<Boolean> condition, Duration timeout, Duration pollInterval) {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
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
        private final CompletableFuture<T> future;
        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private volatile boolean complete = false;

        public FutureCapture(CompletableFuture<T> future) {
            this.future = future;
            future.whenComplete((res, err) -> {
                result.set(res);
                error.set(err);
                complete = true;
            });
        }

        public void awaitDone(Duration timeout) {
            try {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("Condition not met within " + timeout.toMillis() + " ms", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                error.set(cause != null ? cause : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Polling interrupted", e);
            }

            awaitUntil(() -> complete, timeout, Duration.ofMillis(1));
        }

        public T getResult() { return result.get(); }
        public Throwable getError() { return error.get(); }
        public boolean isComplete() { return complete; }
    }
}
