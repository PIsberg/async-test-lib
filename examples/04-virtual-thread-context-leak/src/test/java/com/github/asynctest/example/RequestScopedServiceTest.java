package com.github.asynctest.example;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.example.service.RequestScopedService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates a virtual thread ThreadLocal context leak.
 *
 * <p>The {@link RequestScopedService} stores the current user in a {@code ThreadLocal}
 * but forgets to clear it in the exception path. Under normal unit tests this is
 * invisible; under virtual-thread stress testing it becomes a correctness/security issue.
 *
 * <p><b>Detection:</b> Run with async-test-lib 0.7.0+ and
 * {@code detectVirtualThreadContextLeaks = true} to surface the leak automatically.
 */
class RequestScopedServiceTest {

    private final RequestScopedService service = new RequestScopedService();

    // =========================================================================
    // @Test: passes — single-threaded execution hides the bug
    // =========================================================================

    /**
     * A plain {@code @Test} appears to work because only one request runs at a time.
     * Even though the ThreadLocal is not always cleared, the JVM garbage-collects
     * it between test runs and there is no cross-request contamination observable.
     */
    @Test
    void processRequest_happyPath_appearsToWorkCorrectly() {
        String result = service.processRequest("alice", "order-123");
        assertEquals("Processed order order-123 for user alice", result);
    }

    @Test
    void processRequest_exceptionPath_singleThreaded() {
        // The exception path skips clearCurrentUser(), but we don't notice
        // under sequential execution because the ThreadLocal doesn't leak visibly.
        assertThrows(RuntimeException.class,
            () -> service.processRequest("bob", ""));
    }

    // =========================================================================
    // @AsyncTest: reveals the ThreadLocal not cleaned up under concurrent load
    // =========================================================================

    /**
     * Under virtual-thread concurrent stress the ThreadLocal is not reliably cleared
     * in the exception path.
     *
     * <p>With async-test-lib 0.7.0+ and {@code detectVirtualThreadContextLeaks = true},
     * this test would report:
     * <pre>
     * 🟠 HIGH: Virtual thread ThreadLocal context leak detected
     *   ThreadLocal leaks (set but never removed):
     *     - Virtual thread (id=...): ThreadLocal 'CURRENT_USER' was set but never removed.
     * </pre>
     *
     * <p>This test is commented out because it intentionally exposes the bug in the
     * buggy {@link RequestScopedService}. Uncomment to observe the failure.
     *
     * <p>Fix: add a {@code finally} block in {@link RequestScopedService#processRequest}
     * that always calls {@link RequestScopedService#clearCurrentUser()}.
     */
    // @AsyncTest(
    //     threads = 10,
    //     invocations = 50,
    //     useVirtualThreads = true,
    //     detectVirtualThreadContextLeaks = true  // requires async-test-lib 0.7.0+
    // )
    // void processRequest_concurrentStress_exposesContextLeak() {
    //     // Alternate between valid and invalid orders to trigger the exception path
    //     String orderId = (Thread.currentThread().threadId() % 3 == 0) ? "" : "order-123";
    //     try {
    //         service.processRequest("user-" + Thread.currentThread().threadId(), orderId);
    //     } catch (RuntimeException ignored) {
    //         // BUG: clearCurrentUser() was not called before this exception
    //     }
    // }

    /**
     * Demonstrates the service under concurrent virtual-thread stress with basic detection.
     *
     * <p>With async-test-lib 0.7.0+ you would add {@code detectVirtualThreadContextLeaks = true}
     * to catch the ThreadLocal that is never removed. Here we use only deadlock detection
     * to stay compatible with the published library version.
     */
    @AsyncTest(
        threads = 10,
        invocations = 30,
        useVirtualThreads = true,
        detectAll = false,
        detectDeadlocks = true,
        timeoutMs = 15000
    )
    void processRequest_concurrentVirtualThreads() {
        // Only use valid order IDs — we're testing concurrency, not the exception path
        String userId = "user-" + Thread.currentThread().threadId();
        String result = service.processRequest(userId, "order-" + Thread.currentThread().threadId());
        assertNotNull(result);
        assertTrue(result.startsWith("Processed order"));
    }

    /**
     * Demonstrates that virtual thread stress mode runs without deadlocks.
     */
    @AsyncTest(
        useVirtualThreads = true,
        virtualThreadStressMode = "LOW",
        invocations = 5,
        detectAll = false,
        detectDeadlocks = true,
        timeoutMs = 15000
    )
    void processRequest_virtualThreadStressMode() {
        AtomicReference<String> result = new AtomicReference<>();
        String userId = "stress-user-" + Thread.currentThread().threadId();
        assertDoesNotThrow(() ->
            result.set(service.processRequest(userId, "order-stress"))
        );
        assertNotNull(result.get());
    }
}
