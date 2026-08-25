package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example demonstrating CompletableFutureBlockingCallbackDetector flagging blocking calls in callbacks.
 */
class CompletableFutureBlockingCallbackTest {

    @Test
    void testNonBlockingCallback_doesNotFlag() {
        CompletableFuture.completedFuture("hello")
            .thenApply(s -> s + " world")
            .thenAccept(System.out::println);
    }

    @Disabled("Remove @Disabled to see the bug detected by CompletableFutureBlockingCallbackDetector")
    @AsyncTest(threads = 2, invocations = 5, detectAll = false, detectCFBlockingCallback = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBlockingCallback() {
        var mon = AsyncTestContext.cfBlockingCallbackDetector();
        Thread thread = Thread.currentThread();

        // Simulate entering a callback
        mon.recordEnterCallback("thenApply", thread);
        try {
            // Bug: blocking inside callback (e.g. Thread.sleep)
            mon.recordBlockingCall(thread, "Thread.sleep");
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            // Exit callback
            mon.recordExitCallback(thread);
        }
    }
}
