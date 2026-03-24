package com.github.asynctest;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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
    void testFutureCapture() {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(200); } catch (InterruptedException e) {}
            return "SUCCESS";
        });

        AsyncAssert.FutureCapture<String> capture = AsyncAssert.capture(future);
        capture.awaitDone(Duration.ofSeconds(2));

        assertTrue(capture.isComplete());
        assertEquals("SUCCESS", capture.getResult());
        assertNull(capture.getError());
    }
}
