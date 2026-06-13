package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example demonstrating CompletableFutureObtrudeDetector flagging the use of obtrudeValue.
 */
class CompletableFutureObtrudeTest {

    private CompletableFuture<String> future;

    @BeforeEach
    void setUp() {
        future = new CompletableFuture<>();
    }

    @Test
    void testNormalComplete_doesNotFlag() {
        future.complete("ok");
        assertEquals("ok", future.join());
    }

    @Disabled("Remove @Disabled to see the bug detected by CompletableFutureObtrudeDetector")
    @AsyncTest(threads = 4, invocations = 10, detectAll = false, detectCompletableFutureObtrudeAbuse = true)
    void test_concurrent_detectsObtrudeAbuse() {
        var mon = AsyncTestContext.completableFutureObtrudeDetector();
        Thread thread = Thread.currentThread();

        // Instrument: record the obtrude call
        mon.recordObtrude(future, "shared-future", thread);

        // Buggy action: obtruding the value
        future.obtrudeValue("abused-value");
    }
}
