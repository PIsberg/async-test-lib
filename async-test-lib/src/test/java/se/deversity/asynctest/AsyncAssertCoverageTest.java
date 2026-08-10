package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@link AsyncAssert} branches the happy-path tests miss: polling that ignores
 * transient exceptions, {@link AsyncAssert.FutureCapture#awaitDone} success/failure/timeout,
 * and {@link AsyncAssert#awaitAsync} unwrapping (RuntimeException / Error / checked / timeout)
 * plus its null-argument guards.
 */
class AsyncAssertCoverageTest {

    private static final Duration SHORT = Duration.ofMillis(80);

    @Test
    void awaitUntil_ignoresTransientExceptionsThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        // First call throws (must be swallowed by the poll loop), second returns true.
        AsyncAssert.awaitUntil(() -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("transient");
            return true;
        }, Duration.ofSeconds(2), Duration.ofMillis(1));
        assertTrue(calls.get() >= 2);
    }

    @Test
    void awaitUntil_alwaysThrowingCondition_timesOut() {
        AssertionError err = assertThrows(AssertionError.class, () ->
                AsyncAssert.awaitUntil(() -> { throw new RuntimeException("nope"); },
                        SHORT, Duration.ofMillis(5)));
        assertTrue(err.getMessage().contains("not met"));
    }

    @Test
    void futureCapture_awaitDone_success() {
        CompletableFuture<String> f = CompletableFuture.completedFuture("ok");
        AsyncAssert.FutureCapture<String> cap = AsyncAssert.capture(f);
        cap.awaitDone(Duration.ofSeconds(2));
        assertTrue(cap.isComplete());
        assertEquals("ok", cap.getResult());
        assertNull(cap.getError());
    }

    @Test
    void futureCapture_awaitDone_capturesFailure() {
        CompletableFuture<String> f = new CompletableFuture<>();
        RuntimeException boom = new RuntimeException("boom");
        f.completeExceptionally(boom);
        AsyncAssert.FutureCapture<String> cap = AsyncAssert.capture(f);
        cap.awaitDone(Duration.ofSeconds(2));
        assertTrue(cap.isComplete());
        assertNull(cap.getResult());
        assertSame(boom, cap.getError());
    }

    @Test
    void futureCapture_awaitDone_timesOut() {
        CompletableFuture<String> never = new CompletableFuture<>();
        AsyncAssert.FutureCapture<String> cap = AsyncAssert.capture(never);
        AssertionError err = assertThrows(AssertionError.class, () -> cap.awaitDone(SHORT));
        assertTrue(err.getMessage().contains("Future did not complete within"), err.getMessage());
        assertFalse(cap.isComplete());
    }

    @Test
    void awaitAsync_returnsResolvedValue() {
        String v = AsyncAssert.awaitAsync(CompletableFuture.completedFuture("done"), Duration.ofSeconds(2));
        assertEquals("done", v);
    }

    @Test
    void awaitAsync_unwrapsRuntimeException() {
        CompletableFuture<String> f = new CompletableFuture<>();
        IllegalStateException cause = new IllegalStateException("rt");
        f.completeExceptionally(cause);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> AsyncAssert.awaitAsync(f, Duration.ofSeconds(2)));
        assertSame(cause, thrown);
    }

    @Test
    void awaitAsync_unwrapsError() {
        CompletableFuture<String> f = new CompletableFuture<>();
        AssertionError cause = new AssertionError("err");
        f.completeExceptionally(cause);
        AssertionError thrown = assertThrows(AssertionError.class,
                () -> AsyncAssert.awaitAsync(f, Duration.ofSeconds(2)));
        assertSame(cause, thrown);
    }

    @Test
    void awaitAsync_wrapsCheckedExceptionInAssertionError() {
        CompletableFuture<String> f = new CompletableFuture<>();
        f.completeExceptionally(new IOException("io"));
        AssertionError thrown = assertThrows(AssertionError.class,
                () -> AsyncAssert.awaitAsync(f, Duration.ofSeconds(2)));
        assertInstanceOf(IOException.class, thrown.getCause());
    }

    @Test
    void awaitAsync_timesOut() {
        AssertionError err = assertThrows(AssertionError.class,
                () -> AsyncAssert.awaitAsync(new CompletableFuture<>(), SHORT));
        assertTrue(err.getMessage().contains("did not complete"));
    }

    @Test
    void awaitAsync_rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssert.awaitAsync(null, SHORT));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssert.awaitAsync(CompletableFuture.completedFuture("x"), null));
    }
}
