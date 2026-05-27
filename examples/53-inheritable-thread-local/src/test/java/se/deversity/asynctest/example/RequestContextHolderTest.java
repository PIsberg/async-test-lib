package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.RequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RequestContextHolder.
 *
 * ========================================================================
 * DETECTOR: InheritableThreadLocalMisuseDetector
 * ========================================================================
 *
 * THE BUG:
 * RequestContextHolder uses InheritableThreadLocal<String> to propagate a
 * request ID to child threads. When a thread pool reuses a worker thread, that
 * thread carries the context from the request that originally created it.
 * Subsequent requests reusing the same worker see stale context from a prior
 * request unless the value is explicitly cleared — which is easily forgotten.
 *
 * WHY @Test PASSES:
 * A fresh thread is created for each test invocation, so InheritableThreadLocal
 * behaves like a regular ThreadLocal. No stale inheritance occurs.
 *
 * WHY @AsyncTest DETECTS:
 * InheritableThreadLocalMisuseDetector.registerPoolThread() marks threads as
 * pool threads. recordGet() then flags reads of InheritableThreadLocal values
 * on a pool thread, because the inherited value may be stale from a prior request.
 *
 * FIX:
 * Use plain ThreadLocal and explicitly copy the value into each task via a
 * wrapper Runnable, or use ScopedValue (Java 21+) which does not propagate
 * through thread-pool reuse.
 */
class RequestContextHolderTest {

    @BeforeEach
    void setUp() {
        RequestContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.clear();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testSetAndGet_singleThread_returnsSetValue() {
        RequestContextHolder.setRequestId("req-001");
        assertEquals("req-001", RequestContextHolder.getRequestId());
    }

    @Test
    void testClear_removesValue() {
        RequestContextHolder.setRequestId("req-002");
        RequestContextHolder.clear();
        assertNull(RequestContextHolder.getRequestId(),
                "After clear(), getRequestId() should return null");
    }

    @Test
    void testInheritedByChildThread_demonstratesInheritance() throws Exception {
        RequestContextHolder.setRequestId("req-parent");

        // Fresh thread inherits parent's value — appears to work correctly
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<String> future = exec.submit(RequestContextHolder::getRequestId);
        assertEquals("req-parent", future.get(),
                "Fresh child thread inherits the parent's request ID");
        exec.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 pooled threads each setting and reading a request ID, worker threads
     * created during request A carry request A's context. When reused for request B
     * without clearing, getRequestId() returns the stale request-A ID.
     * InheritableThreadLocalMisuseDetector records and reports this pattern.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: use plain ThreadLocal with explicit propagation, or ScopedValue
     */
    @Disabled("Remove @Disabled to see the bug detected by InheritableThreadLocalMisuseDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectInheritableThreadLocalMisuse = true)
    void testGetRequestId_concurrent_detectsStaleContext() {
        // Mark this thread as a pool thread so the detector knows context may be stale
        AsyncTestContext.inheritableThreadLocalMisuseMonitor()
                .registerPoolThread(Thread.currentThread());

        // Set a request-scoped ID
        String requestId = "req-" + Thread.currentThread().threadId();
        AsyncTestContext.inheritableThreadLocalMisuseMonitor()
                .recordSet(RequestContextHolder.getThreadLocal(), "REQUEST_ID", requestId);
        RequestContextHolder.setRequestId(requestId);

        // Read it back — on a reused pool thread this may return a stale value
        AsyncTestContext.inheritableThreadLocalMisuseMonitor()
                .recordGet(RequestContextHolder.getThreadLocal(), "REQUEST_ID");
        String observed = RequestContextHolder.getRequestId();

        // Intentionally no clear() here — the bug: stale context leaks to the next request
        assertNotNull(observed, "Context should be set for this invocation");
    }
}
