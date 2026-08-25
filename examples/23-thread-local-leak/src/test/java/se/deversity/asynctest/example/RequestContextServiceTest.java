package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.ThreadLocalMonitor;
import se.deversity.asynctest.example.service.RequestContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RequestContextService.
 *
 * ========================================================================
 * DETECTOR: ThreadLocalMonitor
 * ========================================================================
 *
 * This test demonstrates a common security/correctness bug in thread-pool
 * applications where:
 * - A sequential @Test PASSES (the expected user is always returned)
 * - The same test with @AsyncTest + ThreadLocalMonitor reveals that the
 *   ThreadLocal is never cleaned up, making stale auth data visible to
 *   the next request on a reused thread
 *
 * THE BUG:
 * RequestContextService.beginRequest() sets a ThreadLocal, but there is
 * no corresponding endRequest() call to remove it. Under thread-pool
 * concurrency:
 *   - Request 1 on Thread A calls beginRequest("alice")
 *   - Request 1 finishes without calling endRequest()
 *   - Thread A is returned to the pool
 *   - Request 2 is dispatched to Thread A
 *   - Request 2 does NOT call beginRequest() (optional step)
 *   - Request 2 calls getRequestUserId() and sees "alice"!
 *
 * WHY @Test PASSES:
 * Single-threaded execution runs one request at a time. The ThreadLocal
 * is always set before it is read, so the correct user is returned.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * ThreadLocalMonitor is recording-fed: it tracks the lifecycle the code under
 * test reports through recordThreadLocalInit(), recordThreadLocalAccess() and
 * recordThreadLocalCleanup(). RequestContextService.observeLifecycle wires those
 * three to the set, the get and the remove, so a ThreadLocal that was set and
 * never removed shows up as exactly that. failOn = FailOn.LOW turns the finding
 * into a failed run.
 *
 * DETECTOR ENABLED HERE:
 * ThreadLocalMonitor — ThreadLocal set without remove(). It is the only one this
 * demonstration switches on, so it is the only one that can report.
 *
 * FIX:
 * - Always call endRequest() (which calls CURRENT_USER.remove()) in a
 *   finally block so cleanup is guaranteed even when exceptions occur
 */
class RequestContextServiceTest {

    private RequestContextService service;

    @BeforeEach
    void setUp() {
        service = new RequestContextService();
    }

    /**
     * The ThreadLocal is static, so a test that leaves a value behind would hand it to the next
     * one. Removed directly rather than through endRequest(), which would fire the cleanup hook.
     */
    @AfterEach
    void clearThreadLocal() {
        RequestContextService.threadLocal().remove();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, correct user always returned
    // -------------------------------------------------------------------------

    @Test
    void testBeginRequest_singleThread_returnsCorrectUser() {
        service.beginRequest("alice");
        assertEquals("alice", service.getRequestUserId());
        service.endRequest(); // clean up for this test
    }

    @Test
    void testGetRequestUserId_noRequest_returnsNull() {
        // Without beginRequest(), the ThreadLocal initialises to null
        assertNull(service.getRequestUserId());
    }

    @Test
    void testEndRequest_clearsValue() {
        service.beginRequest("bob");
        service.endRequest();
        assertNull(service.getRequestUserId());
    }

    /**
     * Pins the monitor's positive direction without needing the concurrent run: a set with no
     * remove is the leak, and the monitor must say so.
     */
    @Test
    void testThreadLocalMonitor_setWithoutRemove_reports() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        RequestContextService leaky = new RequestContextService();
        leaky.observeLifecycle(
                () -> monitor.recordThreadLocalInit(RequestContextService.threadLocal(), "REQUEST_USER"),
                () -> monitor.recordThreadLocalAccess(RequestContextService.threadLocal()),
                () -> monitor.recordThreadLocalCleanup(RequestContextService.threadLocal()));

        leaky.beginRequest("alice");
        leaky.getRequestUserId();
        // no endRequest()

        assertTrue(monitor.analyzeThreadLocalLeaks().hasIssues(),
                "a ThreadLocal set and never removed is the leak this monitor exists for");
    }

    /**
     * And the other direction: the same lifecycle with the remove in place must stay silent, or
     * the monitor would flag every correct use of a ThreadLocal.
     */
    @Test
    void testThreadLocalMonitor_setThenRemove_isSilent() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        RequestContextService tidy = new RequestContextService();
        tidy.observeLifecycle(
                () -> monitor.recordThreadLocalInit(RequestContextService.threadLocal(), "REQUEST_USER"),
                () -> monitor.recordThreadLocalAccess(RequestContextService.threadLocal()),
                () -> monitor.recordThreadLocalCleanup(RequestContextService.threadLocal()));

        try {
            tidy.beginRequest("alice");
            tidy.getRequestUserId();
        } finally {
            tidy.endRequest();
        }

        assertFalse(monitor.analyzeThreadLocalLeaks().hasIssues(),
                "a ThreadLocal that was removed is not a leak");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the ThreadLocal not cleaned up
    // -------------------------------------------------------------------------

    /**
     * The bug: with 8 threads each calling beginRequest() but never
     * endRequest(), the ThreadLocalMonitor records the ThreadLocal as
     * initialized on multiple threads but never cleaned up.
     * The monitor's analyzeThreadLocalLeaks() report flags it as a
     * likely leak across reused threads.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      REQUEST_USER: accessed by N thread(s) without remove()
     *      REQUEST_USER: value crossed N reused thread(s)
     *    N counts distinct thread ids, and the runner gives every body execution its own
     *    virtual thread, so it is the number of executions rather than the 8 configured
     *    threads. See issue #349.
     * 3. Fix: add a finally block that always calls endRequest()
     */
    @Disabled("Remove @Disabled to see ThreadLocal leak detected by ThreadLocalMonitor")
    @AsyncTest(threads = 8, invocations = 20, detectAll = false, detectThreadLocalLeaks = true, failOn = FailOn.LOW)
    void testBeginRequest_concurrent_detectsThreadLocalLeak() {
        // The monitor has to be the one the run owns. This demonstration used to record into a
        // locally constructed ThreadLocalMonitor and assert on it from @AfterEach; the library
        // never reads that instance, so failOn had nothing to gate on and enabling the test left
        // it green. See issue #346.
        ThreadLocalMonitor monitor = AsyncTestContext.threadLocalMonitor();
        ThreadLocal<String> threadLocal = RequestContextService.threadLocal();
        service.observeLifecycle(
                () -> monitor.recordThreadLocalInit(threadLocal, "REQUEST_USER"),
                () -> monitor.recordThreadLocalAccess(threadLocal),
                () -> monitor.recordThreadLocalCleanup(threadLocal));

        service.beginRequest("user-" + Thread.currentThread().threadId());
        assertNotNull(service.getRequestUserId());

        // BUG: endRequest() is never called, so CURRENT_USER.remove() never happens and the
        // cleanup hook never fires. The value stays on the thread for whatever runs next.
    }

    /**
     * Fixed version: endRequest() is always called in a finally block,
     * guaranteeing the ThreadLocal is removed before the thread returns
     * to the pool.
     */
    @Test
    void testBeginRequest_fixedWithFinally_singleThread() {
        String userId = "charlie";
        try {
            service.beginRequest(userId);
            assertEquals(userId, service.getRequestUserId());
        } finally {
            service.endRequest(); // ✅ always cleans up
        }
        // After finally: ThreadLocal is null — no stale data for the next request
        assertNull(service.getRequestUserId());
    }
}
