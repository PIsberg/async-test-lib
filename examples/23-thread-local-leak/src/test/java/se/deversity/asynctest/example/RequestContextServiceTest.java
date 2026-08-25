package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
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
 * ThreadLocalMonitor tracks every recordThreadLocalInit() and
 * recordThreadLocalAccess() call. When the ThreadLocal is initialized
 * (set) but recordThreadLocalCleanup() is never called, the monitor
 * flags it as "missing cleanup" and — when multiple threads used the
 * same ThreadLocal — as a "likely leak across reused threads".
 * The @AfterEach assertion verifies that detection fired.
 *
 * DETECTORS TRIGGERED:
 * ThreadLocalMonitor — Primary: ThreadLocal set without remove()
 *
 * FIX:
 * - Always call endRequest() (which calls CURRENT_USER.remove()) in a
 *   finally block so cleanup is guaranteed even when exceptions occur
 */
class RequestContextServiceTest {

    private RequestContextService service;
    private ThreadLocalMonitor threadLocalMonitor;
    // Guard flag so the @AfterEach assertion only runs after the @AsyncTest.
    private volatile boolean runningAsyncTest = false;

    @BeforeEach
    void setUp() {
        service = new RequestContextService();
        threadLocalMonitor = new ThreadLocalMonitor();
    }

    /**
     * After the @AsyncTest run completes, verify the monitor detected the
     * uncleaned ThreadLocal. @AfterEach runs once after all threads and
     * invocations finish.
     */
    @AfterEach
    void verifyThreadLocalLeakDetected() {
        if (!runningAsyncTest) {
            // Ensure no stale state leaks between plain @Test methods
            service.endRequest();
            return;
        }
        ThreadLocalMonitor.ThreadLocalReport report = threadLocalMonitor.analyzeThreadLocalLeaks();
        assertTrue(report.hasIssues(),
                "ThreadLocalMonitor should have flagged the missing remove().\n" + report);
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

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the ThreadLocal not cleaned up
    // -------------------------------------------------------------------------

    /**
     * The bug: with 8 threads each calling beginRequest() but never
     * endRequest(), the ThreadLocalMonitor records the ThreadLocal as
     * initialized on multiple threads but never cleaned up.
     * The monitor's analyzeThreadLocalLeaks() report flags it as a
     * likely leak across reused threads.
     * The @AfterEach assertion verifies that detection fired after the run.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — @AfterEach will assert that ThreadLocalMonitor
     *    flagged "REQUEST_USER" as missing cleanup across multiple threads
     * 3. Fix: add a finally block that always calls endRequest()
     */
    @Disabled("Remove @Disabled to see ThreadLocal leak detected by ThreadLocalMonitor")
    @AsyncTest(threads = 8, invocations = 20, detectAll = false, detectThreadLocalLeaks = true, failOn = FailOn.LOW)
    void testBeginRequest_concurrent_detectsThreadLocalLeak() {
        runningAsyncTest = true;
        String userId = "user-" + Thread.currentThread().threadId();

        // Record that the ThreadLocal is being initialized on this thread.
        // Uses the same shared ThreadLocalMonitor so all threads contribute
        // to a single aggregate lifecycle report.
        threadLocalMonitor.recordThreadLocalInit(
                RequestContextService.threadLocal(), "REQUEST_USER");

        // Set the value — this is the buggy path that never calls endRequest()
        service.beginRequest(userId);

        // Record an access so the monitor tracks which threads used this ThreadLocal
        threadLocalMonitor.recordThreadLocalAccess(RequestContextService.threadLocal());

        // Read the user (should be this thread's userId in a single-thread run)
        String observed = service.getRequestUserId();
        assertNotNull(observed);

        // BUG: endRequest() / CURRENT_USER.remove() is NOT called.
        // threadLocalMonitor.recordThreadLocalCleanup() is never called.
        // After the @AsyncTest run, @AfterEach calls analyzeThreadLocalLeaks()
        // and asserts "REQUEST_USER: accessed by N threads without remove()".
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
