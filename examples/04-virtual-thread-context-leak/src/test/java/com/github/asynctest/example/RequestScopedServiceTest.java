package com.github.asynctest.example;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.AsyncTestContext;
import com.github.asynctest.example.service.RequestScopedService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates a virtual thread ThreadLocal context leak.
 *
 * <p>The {@link RequestScopedService} stores the current user in a {@code ThreadLocal}
 * but forgets to clear it in the exception path. Under normal unit tests this is
 * invisible; under virtual-thread stress testing it becomes a security/correctness bug.
 */
class RequestScopedServiceTest {

    private final RequestScopedService service = new RequestScopedService();

    // =========================================================================
    // @Test: passes — single-threaded execution hides the bug
    // =========================================================================

    /**
     * A plain @Test appears to work because only one request runs at a time.
     * Even though the ThreadLocal is not always cleared, the JVM garbage-collects
     * it between test runs and there is no cross-request contamination observable.
     */
    @Test
    void processRequest_appearsToWorkCorrectly() {
        // Happy path — no exception, ThreadLocal gets cleared properly in this branch
        String result = service.processRequest("alice", "order-123");
        assertEquals("Processed order order-123 for user alice", result);
        // ThreadLocal was cleared — looks fine
    }

    @Test
    void processRequest_exceptionCase_singleThreaded() {
        // The exception path skips clearCurrentUser(), but we don't notice
        // because the ThreadLocal is cleaned up between test methods anyway
        assertThrows(RuntimeException.class,
            () -> service.processRequest("bob", ""));
    }

    // =========================================================================
    // @AsyncTest: exposes the bug under virtual thread concurrency
    // =========================================================================

    /**
     * THIS IS THE TEST THAT EXPOSES THE BUG.
     *
     * <p>Under virtual-thread concurrent stress, the {@code VirtualThreadContextLeakDetector}
     * tracks every {@code ThreadLocal.set()} and {@code ThreadLocal.remove()} call.
     * When the exception path skips {@code clearCurrentUser()}, the detector reports:
     *
     * <pre>
     * 🟠 HIGH: Virtual thread ThreadLocal context leak detected
     *   ThreadLocal leaks (set but never removed):
     *     - Virtual thread (id=...): ThreadLocal 'CURRENT_USER' was set but never removed.
     *       This value will persist and may leak into subsequent tasks on the same thread.
     * </pre>
     *
     * <p>The annotation is commented out because running it against the buggy service
     * intentionally fails. Uncomment to see the detector in action.
     *
     * <p>To fix: add a {@code finally} block that calls {@code service.clearCurrentUser()}.
     */
    // @AsyncTest(
    //     threads = 10,
    //     invocations = 50,
    //     useVirtualThreads = true,
    //     detectVirtualThreadContextLeaks = true
    // )
    // void processRequest_virtualThreadContextLeak() {
    //     var detector = AsyncTestContext.virtualThreadContextLeakDetector();
    //     Thread current = Thread.currentThread();
    //     String userId = "user-" + current.threadId();
    //
    //     detector.recordThreadLocalSet("CURRENT_USER", current);
    //     try {
    //         // Alternate between valid and invalid order IDs to trigger the exception path
    //         String orderId = (current.threadId() % 3 == 0) ? "" : "order-" + current.threadId();
    //         service.processRequest(userId, orderId);
    //         detector.recordThreadLocalRemoved("CURRENT_USER", current);
    //     } catch (RuntimeException e) {
    //         // BUG: clearCurrentUser() was NOT called in the exception path
    //         // detector.recordThreadLocalRemoved("CURRENT_USER", current); ← missing!
    //     }
    // }

    /**
     * Demonstrates the FIXED version of the test — ThreadLocal is always removed.
     */
    @AsyncTest(
        threads = 10,
        invocations = 50,
        useVirtualThreads = true,
        detectVirtualThreadContextLeaks = true,
        timeoutMs = 15000
    )
    void processRequest_virtualThreadContextLeak_fixed() {
        var detector = AsyncTestContext.virtualThreadContextLeakDetector();
        Thread current = Thread.currentThread();
        String userId = "user-" + current.threadId();

        detector.recordThreadLocalSet("CURRENT_USER", current);
        try {
            String orderId = "order-" + current.threadId(); // always valid
            service.processRequest(userId, orderId);
        } finally {
            service.clearCurrentUser();
            detector.recordThreadLocalRemoved("CURRENT_USER", current);
        }
    }

    /**
     * Demonstrates correct ScopedValue usage tracking as an alternative to ThreadLocal.
     * ScopedValue is the preferred approach for virtual threads — it cannot leak.
     */
    @AsyncTest(
        threads = 8,
        invocations = 30,
        useVirtualThreads = true,
        detectScopedValueMisuse = true,
        timeoutMs = 15000
    )
    void demonstrateScopedValueProperUsage() {
        var detector = AsyncTestContext.scopedValueMisuseDetector();
        Thread current = Thread.currentThread();
        String svName = "CURRENT_USER_SV";

        // Simulate ScopedValue.where(CURRENT_USER_SV, userId).run(() -> { ... })
        detector.recordBindingEntered(svName, current);
        detector.recordGetCalled(svName, current);  // safe — within binding
        detector.recordBindingExited(svName, current);
        // After exit: get() would throw NoSuchElementException — correctly scoped
    }

    /**
     * Demonstrates correct StructuredTaskScope tracking.
     * Every scope is opened, has subtasks forked, joined, and closed.
     */
    @AsyncTest(
        threads = 4,
        invocations = 20,
        useVirtualThreads = true,
        detectStructuredConcurrencyIssues = true,
        timeoutMs = 15000
    )
    void demonstrateStructuredConcurrencyProperUsage() {
        var detector = AsyncTestContext.structuredConcurrencyMisuseDetector();

        // Simulate: try (var scope = new StructuredTaskScope.ShutdownOnFailure()) { ... }
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        detector.recordSubtaskForked(scopeId);
        detector.recordJoinCalled(scopeId);
        detector.recordResultAccessed(scopeId);
        detector.recordResultAccessed(scopeId);
        detector.recordScopeClosed(scopeId);
    }
}
