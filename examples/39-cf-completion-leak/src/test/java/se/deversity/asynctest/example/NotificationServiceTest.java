package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for NotificationService.
 *
 * ========================================================================
 * DETECTOR: CompletableFutureCompletionLeakDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * NotificationService.sendNotification() creates a CompletableFuture per
 * message and adds it to a list, but never calls complete(). Any caller that
 * awaits the returned future will block forever. Under load the list grows
 * without bound and all waiting threads hang indefinitely.
 *
 * WHY @Test PASSES:
 * The single-threaded test calls sendNotification() and gets back the future,
 * but never calls .get() on it — so the hang never manifests. The returned
 * future is simply discarded and the test finishes.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads create futures rapidly. CompletableFutureCompletionLeakDetector
 * tracks each registered future and checks whether complete() or
 * completeExceptionally() is called within the test window. Futures that
 * remain incomplete at analysis time are reported as leaks.
 *
 * DETECTORS TRIGGERED:
 *   CompletableFutureCompletionLeakDetector — primary: uncompleted futures
 *
 * FIX: call delivery.complete(null) after the notification is dispatched.
 */
class NotificationServiceTest {

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes (future is discarded, never awaited)
    // -----------------------------------------------------------------------

    @Test
    void testSendNotification_returnsNonNullFuture() {
        CompletableFuture<Void> future = service.sendNotification("hello");
        assertNotNull(future, "sendNotification must return a non-null future");
    }

    @Test
    void testPendingCount_incrementsPerCall() {
        service.sendNotification("msg-1");
        service.sendNotification("msg-2");
        assertEquals(2, service.pendingCount());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes leaked (never-completed) futures
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see completion leaks detected by CompletableFutureCompletionLeakDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCompletableFutureCompletionLeaks = true, failOn = FailOn.LOW)
    void testNotify_concurrent_detectsCompletionLeak() {
        String message = "notification-" + Thread.currentThread().getId();

        // Call the buggy service — returns a future that is never completed
        CompletableFuture<Void> future = service.sendNotification(message);

        // Register the future with the detector
        AsyncTestContext.get().completableFutureCompletionLeakDetector()
                .recordFutureCreated(future, "notification-future");

        // BUG: we do not call future.complete(null) — detector will flag this
        // as a leaked future at analysis time.

        assertNotNull(future, "future reference must not be null");
        assertFalse(future.isDone(), "future must still be incomplete (bug confirmed)");
    }
}
