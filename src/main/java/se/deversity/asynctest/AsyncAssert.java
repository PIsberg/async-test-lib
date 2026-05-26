package se.deversity.asynctest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@AIContract(reason = "Public assertion utility API for AsyncTest consumers. awaitUntil() and capture() are used directly in user test code — method signatures and semantics must not change without a major version bump.")
@AIPublicAPI
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
            } catch (Exception e) { // NOPMD EmptyCatchBlock — polling deliberately ignores transient failures
                // Ignore exceptions during polling, just keep trying
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Polling interrupted", e);
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

        @SuppressFBWarnings("EI_EXPOSE_REP2")
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

    /**
     * Blocks until the given async chain completes (or fails) and unwraps the
     * usual {@link ExecutionException} wrapper so that user assertions inside the
     * chain surface as the original exception type.
     *
     * <p>Designed for use inside {@code @AsyncTest} method bodies that exercise
     * async APIs:
     * <pre>{@code
     * @AsyncTest
     * void hammer_async_pipeline() {
     *     CompletableFuture<String> result = service.processAsync(input);
     *     String value = AsyncAssert.awaitAsync(result, Duration.ofSeconds(5));
     *     assertEquals("ok", value);
     * }
     * }</pre>
     *
     * <p>JUnit Jupiter requires {@code @TestTemplate}/{@code @AsyncTest} methods
     * to return {@code void}, so returning a {@code CompletableFuture} from the
     * test body is not an option — this helper is the supported way to await
     * an async chain from inside the body.
     *
     * @param stage   the async chain to await (non-null)
     * @param timeout maximum time to wait
     * @param <T>     the chain's result type
     * @return the resolved value, or throws on failure / timeout
     * @since 1.5.0
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // cause is already the unwrapped original; re-throwing it preserves the trace
    public static <T> T awaitAsync(CompletionStage<T> stage, Duration timeout) {
        if (stage == null) throw new IllegalArgumentException("stage must not be null");
        if (timeout == null) throw new IllegalArgumentException("timeout must not be null");
        try {
            return stage.toCompletableFuture()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "Async stage did not complete within " + timeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new AssertionError("Async stage failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting async stage", e);
        }
    }
}
