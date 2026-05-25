package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.RequestScopedService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Demonstrates {@code ThreadLocalContaminationDetector}.
 *
 * <p>The passing tests show correct single-threaded use. The disabled test
 * exposes the bug: {@code endRequest()} never removes the {@code ThreadLocal}
 * value, so a reused thread carries a stale request ID into the next task.
 * The detector observes a {@code set} without a subsequent {@code remove} and
 * reports contamination.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class RequestScopedServiceTest {

    private RequestScopedService service;

    @BeforeEach
    void setUp() {
        service = new RequestScopedService();
        // Clean up any residual ThreadLocal state from a previous test.
        RequestScopedService.REQUEST_ID.remove();
    }

    @Test
    void test_singleThread_startAndProcess() {
        service.startRequest("req-001");
        String result = service.processRequest();
        assertEquals("Processed: req-001", result);
        service.endRequest();
    }

    @Test
    void test_singleThread_getCurrentIdAfterStart() {
        service.startRequest("req-xyz");
        assertNotNull(service.getCurrentId());
        assertEquals("req-xyz", service.getCurrentId());
        service.endRequest();
    }

    /**
     * Remove {@code @Disabled} to see {@code ThreadLocalContaminationDetector}
     * report ThreadLocal values that were set but never removed.
     *
     * <p>Each invocation calls {@code startRequest()} then {@code endRequest()}.
     * Because {@code endRequest()} does not call {@code remove()}, the value
     * stays bound to the thread. The detector tracks set/get/new-task events
     * and flags threads that start a new task while still carrying a value from
     * the previous one.
     */
    @Disabled("Remove @Disabled to see bug detected by ThreadLocalContaminationDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectThreadLocalContamination = true)
    void test_concurrent_detectsContamination() {
        Thread thread = Thread.currentThread();
        String requestId = "req-" + thread.threadId() + "-" + System.nanoTime();

        // Notify detector a new task is starting on this thread.
        AsyncTestContext.threadLocalContaminationMonitor()
                .recordNewTask(thread, "process-" + requestId);

        service.startRequest(requestId);

        // Notify detector the ThreadLocal was set.
        AsyncTestContext.threadLocalContaminationMonitor()
                .recordSet(thread, RequestScopedService.REQUEST_ID, "REQUEST_ID");

        service.processRequest();

        // Notify detector the ThreadLocal was read.
        AsyncTestContext.threadLocalContaminationMonitor()
                .recordGet(thread, RequestScopedService.REQUEST_ID, "REQUEST_ID", true);

        // BUG: endRequest() does not call remove() — the value lingers.
        service.endRequest();
    }
}
