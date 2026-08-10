package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
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

/**
 * Utility class providing asynchronous assertions and polling mechanisms.
 *
 * <p>These methods allow test code to wait for asynchronous conditions to be met
 * without hardcoding thread sleep times, which makes tests less flaky and faster.
 *
 * @since 1.0.0
 */
@AIContract(reason = "Public assertion utility API for AsyncTest consumers. awaitUntil() and capture() are used directly in user test code — method signatures and semantics must not change without a major version bump.")
@AIPublicAPI
@API(status = Status.STABLE)
public class AsyncAssert {

    /** Poll interval used by the {@code awaitUntil} overloads that do not take one. */
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(10);

    /**
     * Polls the condition until it returns true. Throws AssertionError if the timeout is reached.
     *
     * @param condition the predicate to poll; exceptions thrown during polling are treated as
     *                   "not yet true" and ignored
     * @param timeout   maximum time to wait before failing
     * @throws AssertionError if {@code timeout} elapses before {@code condition} returns {@code true}
     */
    public static void awaitUntil(Callable<Boolean> condition, Duration timeout) {
        awaitUntil(condition, timeout, DEFAULT_POLL_INTERVAL, null);
    }

    /**
     * Polls the condition until it returns true, labelling the failure with {@code description}.
     *
     * <p>The description is what turns "Condition not met within 5000 ms" into a message that
     * names the thing being waited for, which is the difference between a diagnosable CI failure
     * and a rerun.
     *
     * @param condition   the predicate to poll; exceptions thrown during polling are treated as
     *                    "not yet true" and ignored
     * @param timeout     maximum time to wait before failing
     * @param description what the caller is waiting for, e.g. {@code "queue drained"}
     * @throws AssertionError if {@code timeout} elapses before {@code condition} returns {@code true}
     * @since 1.9.0
     */
    public static void awaitUntil(Callable<Boolean> condition, Duration timeout, String description) {
        awaitUntil(condition, timeout, DEFAULT_POLL_INTERVAL, description);
    }

    /**
     * Polls the condition until it returns true, sleeping {@code pollInterval} between attempts.
     * Throws AssertionError if the timeout is reached.
     *
     * @param condition    the predicate to poll; exceptions thrown during polling are treated as
     *                     "not yet true" and ignored
     * @param timeout      maximum time to wait before failing
     * @param pollInterval time to sleep between poll attempts
     * @throws AssertionError if {@code timeout} elapses before {@code condition} returns {@code true},
     *                        or if the polling thread is interrupted
     */
    public static void awaitUntil(Callable<Boolean> condition, Duration timeout, Duration pollInterval) {
        awaitUntil(condition, timeout, pollInterval, null);
    }

    /**
     * Polls the condition until it returns true, sleeping {@code pollInterval} between attempts
     * and labelling the failure with {@code description}.
     *
     * <p>The condition is evaluated before the first sleep and again after the last one, so a
     * condition that turns true inside the timeout window is never reported as a timeout, and a
     * poll interval longer than the remaining budget is clamped rather than slept through. The
     * timeout therefore bounds the sleeping, not the number of evaluations: the condition is
     * always evaluated at least once, including at {@link Duration#ZERO}.
     *
     * <p>Only {@link Exception} is treated as "not yet true". An {@link Error} thrown by the
     * condition — including the {@link AssertionError} of a nested assertion — propagates
     * immediately rather than being retried until the deadline.
     *
     * @param condition    the predicate to poll; exceptions thrown during polling are treated as
     *                     "not yet true" and reported on failure
     * @param timeout      maximum time to wait before failing
     * @param pollInterval time to sleep between poll attempts
     * @param description  what the caller is waiting for, or {@code null} for no label
     * @throws IllegalArgumentException if {@code condition}, {@code timeout} or {@code pollInterval} is null
     * @throws AssertionError if {@code timeout} elapses before {@code condition} returns {@code true},
     *                        or if the polling thread is interrupted
     * @since 1.9.0
     */
    public static void awaitUntil(Callable<Boolean> condition, Duration timeout,
                                  Duration pollInterval, @Nullable String description) {
        if (condition == null) throw new IllegalArgumentException("condition must not be null");
        if (timeout == null) throw new IllegalArgumentException("timeout must not be null");
        if (pollInterval == null) throw new IllegalArgumentException("pollInterval must not be null");

        long deadline = System.nanoTime() + timeout.toNanos();
        Exception lastError = null;
        int attempts = 0;

        while (true) {
            attempts++;
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
                lastError = null;
            } catch (Exception e) { // NOPMD AvoidCatchingGenericException — polling deliberately retries transient failures
                lastError = e;
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            // Never sleep past the deadline: a poll interval longer than the remaining budget
            // used to burn the whole interval and then report a timeout without looking again.
            long sleepMs = Math.min(pollInterval.toMillis(), TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
            try {
                Thread.sleep(Math.max(0, sleepMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Polling interrupted", e);
            }
        }

        throw timeoutError(timeout, description, attempts, lastError);
    }

    private static AssertionError timeoutError(Duration timeout, @Nullable String description,
                                               int attempts, @Nullable Exception lastError) {
        StringBuilder message = new StringBuilder("Condition not met within ")
                .append(timeout.toMillis()).append(" ms");
        if (description != null && !description.isBlank()) {
            message.append(" (").append(description).append(')');
        }
        message.append(" after ").append(attempts).append(" evaluation(s)");
        if (lastError != null) {
            message.append("; last evaluation threw ")
                   .append(lastError.getClass().getName()).append(": ").append(lastError.getMessage());
        }
        return lastError == null
                ? new AssertionError(message.toString())
                : new AssertionError(message.toString(), lastError);
    }

    /**
     * Captures the result or exception of a CompletableFuture non-blockingly,
     * making it available for later assertions without blocking the current thread.
     *
     * @param future the future to observe (non-null)
     * @param <T>    the future's result type
     * @return a {@link FutureCapture} that records the future's outcome as it completes
     */
    public static <T> FutureCapture<T> capture(CompletableFuture<T> future) {
        return new FutureCapture<>(future);
    }

    /**
     * Captures the outcome of any {@link CompletionStage}, so callers holding the interface
     * type do not have to convert before observing it.
     *
     * @param stage the stage to observe (non-null)
     * @param <T>   the stage's result type
     * @return a {@link FutureCapture} that records the stage's outcome as it completes
     * @throws IllegalArgumentException if {@code stage} is null
     * @since 1.9.0
     */
    public static <T> FutureCapture<T> capture(CompletionStage<T> stage) {
        if (stage == null) throw new IllegalArgumentException("stage must not be null");
        return new FutureCapture<>(stage.toCompletableFuture());
    }

    /**
     * Observes a {@link CompletableFuture} non-blockingly, recording its result or
     * exception as soon as it completes so later assertions can inspect it without
     * blocking. Obtain an instance via {@link #capture(CompletableFuture)}.
     *
     * @param <T> the future's result type
     */
    public static class FutureCapture<T> {
        private final CompletableFuture<T> future;
        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private volatile boolean complete = false;

        /**
         * Registers a completion callback on {@code future} so its result or exception
         * is captured as soon as it completes.
         *
         * @param future the future to observe (non-null)
         */
        @SuppressFBWarnings("EI_EXPOSE_REP2")
        @SuppressWarnings("FutureReturnValueIgnored")
        public FutureCapture(CompletableFuture<T> future) {
            this.future = future;
            future.whenComplete((res, err) -> {
                result.set(res);
                error.set(err);
                complete = true;
            });
        }

        /**
         * Blocks until the observed future completes, or throws if {@code timeout} elapses first.
         *
         * @param timeout maximum time to wait
         * @throws AssertionError if the future does not complete within {@code timeout},
         *                        or if the waiting thread is interrupted
         */
        public void awaitDone(Duration timeout) {
            try {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError(
                        "Future did not complete within " + timeout.toMillis() + " ms", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                error.set(cause != null ? cause : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Polling interrupted", e);
            }

            awaitUntil(() -> complete, timeout, Duration.ofMillis(1), "future completion callback");
        }

        /**
         * Get result.
         *
         * @return the future's resolved value, or {@code null} if it has not completed
         *         successfully yet (or completed exceptionally)
         */
        public @Nullable T getResult() { return result.get(); }

        /**
         * Get error.
         *
         * @return the exception the future completed with, or {@code null} if it has not
         *         completed exceptionally
         */
        public @Nullable Throwable getError() { return error.get(); }

        /**
         * Is complete.
         *
         * @return {@code true} once the observed future has completed, successfully or not
         */
        public boolean isComplete() { return complete; }

        /**
         * Whether the future completed with a value. A future resolved to {@code null} counts
         * as a success, which {@link #getResult()} alone cannot express.
         *
         * @return {@code true} if the future has completed without an exception
         * @since 1.9.0
         */
        public boolean isSuccess() { return complete && error.get() == null; }

        /**
         * Whether the future completed with an exception.
         *
         * @return {@code true} if the future has completed exceptionally
         * @since 1.9.0
         */
        public boolean isFailed() { return error.get() != null; }

        /**
         * Returns the captured value, or fails the test with the reason it is unavailable.
         *
         * <p>{@link #getResult()} returns {@code null} for "still running", "failed" and
         * "completed with null" alike; this method separates the three so a test asserting on
         * the value does not have to.
         *
         * @return the future's resolved value (which may itself be {@code null})
         * @throws AssertionError if the future has not completed, or completed exceptionally —
         *                        in which case the failure is attached as the cause
         * @since 1.9.0
         */
        public @Nullable T requireResult() {
            Throwable failure = error.get();
            if (failure != null) {
                throw new AssertionError("Future completed exceptionally: " + failure, failure);
            }
            if (!complete) {
                throw new AssertionError("Future has not completed; call awaitDone(...) first");
            }
            return result.get();
        }
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
     * @since 1.6.0
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
