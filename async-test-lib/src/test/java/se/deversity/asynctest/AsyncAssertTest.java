package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AsyncAssertTest {

    @Test
    void testAwaitUntilSuccess() {
        long[] counter = {0};
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException e) {}
            counter[0] = 1;
        }).start();

        AsyncAssert.awaitUntil(() -> counter[0] == 1, Duration.ofSeconds(2));
        assertEquals(1, counter[0]);
    }

    @Test
    void testAwaitUntilTimeout() {
        assertThrows(AssertionError.class, () -> {
            AsyncAssert.awaitUntil(() -> false, Duration.ofMillis(100));
        });
    }

    @Test
    void testFutureCapture() throws InterruptedException {
        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            started.countDown();
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            future.complete("SUCCESS");
        });
        producer.start();
        started.await();

        AsyncAssert.FutureCapture<String> capture = AsyncAssert.capture(future);
        capture.awaitDone(Duration.ofSeconds(5));
        producer.join();

        assertTrue(capture.isComplete());
        assertEquals("SUCCESS", capture.getResult());
        assertNull(capture.getError());
    }

    // ---- awaitUntil: the condition is always evaluated, and evaluated last ----

    @Test
    void awaitUntil_evaluatesTheConditionAtLeastOnce_whenTheTimeoutIsZero() {
        AtomicInteger calls = new AtomicInteger();

        AsyncAssert.awaitUntil(() -> { calls.incrementAndGet(); return true; }, Duration.ZERO);

        assertEquals(1, calls.get(),
                "A zero timeout must still evaluate the condition once, not fail unseen");
    }

    @Test
    void awaitUntil_clampsThePollIntervalToTheRemainingTime_andEvaluatesAfterTheLastSleep() {
        long startNs = System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        // Poll interval far larger than the timeout, and a condition that only turns true on
        // the second evaluation. Sleeping the whole interval would blow the 80 ms deadline and
        // report a timeout for a condition that was true well inside it.
        AsyncAssert.awaitUntil(
                () -> calls.incrementAndGet() >= 2,
                Duration.ofMillis(80),
                Duration.ofSeconds(10));

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertEquals(2, calls.get(), "The condition must be re-evaluated after the sleep");
        assertTrue(elapsedMs < 5_000L,
                "The poll interval must be clamped to the remaining time; took " + elapsedMs + " ms");
    }

    @Test
    void awaitUntil_timeoutMessage_carriesTheDescriptionAndTheLastThrownException() {
        IllegalStateException boom = new IllegalStateException("queue not ready");

        AssertionError error = assertThrows(AssertionError.class, () ->
                AsyncAssert.awaitUntil(() -> { throw boom; }, Duration.ofMillis(30), "queue drained"));

        assertTrue(error.getMessage().contains("Condition not met within 30 ms"),
                "Message must keep the timeout: " + error.getMessage());
        assertTrue(error.getMessage().contains("queue drained"),
                "Message must carry the caller's description: " + error.getMessage());
        assertTrue(error.getMessage().contains("queue not ready"),
                "Message must surface the swallowed exception: " + error.getMessage());
        assertSame(boom, error.getCause(),
                "The last exception thrown while polling must be attached as the cause");
    }

    @Test
    void awaitUntil_rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssert.awaitUntil(null, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssert.awaitUntil(() -> true, null));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssert.awaitUntil(() -> true, Duration.ofMillis(1), (Duration) null));
    }

    // ---- FutureCapture: outcome is no longer encoded as a nullable getter ----

    @Test
    void capture_acceptsACompletionStage() {
        CompletionStage<String> stage = CompletableFuture.completedFuture("ok");

        AsyncAssert.FutureCapture<String> capture = AsyncAssert.capture(stage);
        capture.awaitDone(Duration.ofSeconds(1));

        assertTrue(capture.isSuccess());
        assertFalse(capture.isFailed());
        assertEquals("ok", capture.requireResult());
    }

    @Test
    void requireResult_onAFailedFuture_throwsWithTheFailureAsCause() {
        IllegalStateException boom = new IllegalStateException("upstream died");
        CompletableFuture<String> future = new CompletableFuture<>();
        future.completeExceptionally(boom);

        AsyncAssert.FutureCapture<String> capture = AsyncAssert.capture(future);
        capture.awaitDone(Duration.ofSeconds(1));

        assertTrue(capture.isFailed());
        assertFalse(capture.isSuccess());
        AssertionError error = assertThrows(AssertionError.class, capture::requireResult);
        assertSame(boom, error.getCause());
    }

    @Test
    void requireResult_onAnIncompleteFuture_throwsRatherThanReturningNull() {
        AsyncAssert.FutureCapture<String> capture = AsyncAssert.capture(new CompletableFuture<String>());

        assertFalse(capture.isComplete());
        assertFalse(capture.isSuccess());
        assertFalse(capture.isFailed());
        AssertionError error = assertThrows(AssertionError.class, capture::requireResult);
        assertTrue(error.getMessage().contains("has not completed"), error.getMessage());
    }

    @Test
    void aFutureCompletedWithNull_countsAsSuccess() {
        AsyncAssert.FutureCapture<String> capture =
                AsyncAssert.capture(CompletableFuture.completedFuture(null));
        capture.awaitDone(Duration.ofSeconds(1));

        assertTrue(capture.isSuccess(), "A null result is a completed future, not a missing one");
        assertNull(capture.requireResult());
    }
}
