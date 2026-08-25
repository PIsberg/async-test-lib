package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.ThreadLocalContaminationDetector;
import se.deversity.asynctest.example.service.RequestScopedService;

import static org.junit.jupiter.api.Assertions.*;

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
     * The detector's positive direction, on one thread pretending to be a pool worker: the
     * value set in the first task is still there when the second task reads it.
     */
    @Test
    void testThreadLocalContaminationDetector_valueSurvivesIntoTheNextTask_reports() {
        ThreadLocalContaminationDetector detector = new ThreadLocalContaminationDetector();
        wire(detector);
        Thread worker = Thread.currentThread();

        detector.recordNewTask(worker, "task-1");
        service.startRequest("req-001");
        service.processRequest();
        service.endRequest();                 // BUG: no remove()

        detector.recordNewTask(worker, "task-2");
        assertEquals("req-001", service.getCurrentId(),
                "the second task inherits the first task's request id");

        assertTrue(detector.analyze().hasIssues(),
                "reading a value set in an earlier task is the contamination");
    }

    /**
     * And the other direction, with the missing remove() put back. The second task reads null,
     * which is what it should read, and there is nothing to report.
     */
    @Test
    void testThreadLocalContaminationDetector_valueRemoved_isSilent() {
        ThreadLocalContaminationDetector detector = new ThreadLocalContaminationDetector();
        wire(detector);
        Thread worker = Thread.currentThread();

        detector.recordNewTask(worker, "task-1");
        service.startRequest("req-002");
        service.processRequest();
        service.endRequestFixed();            // the one line the bug leaves out

        detector.recordNewTask(worker, "task-2");
        assertNull(service.getCurrentId(), "nothing carried over");

        assertFalse(detector.analyze().hasIssues(),
                "a ThreadLocal that was cleared between tasks is correct use");
    }

    private void wire(ThreadLocalContaminationDetector detector) {
        service.observeContext(
                () -> detector.recordSet(Thread.currentThread(),
                        RequestScopedService.REQUEST_ID, "REQUEST_ID"),
                value -> detector.recordGet(Thread.currentThread(),
                        RequestScopedService.REQUEST_ID, "REQUEST_ID", value != null));
    }

    /**
     * Remove {@code @Disabled} to see {@code ThreadLocalContaminationDetector} report a request
     * id that outlived the request it belonged to.
     *
     * <p>Two things about this demonstration are load-bearing.
     *
     * <p>{@code useVirtualThreads = false}. This detector's whole subject is a thread pool
     * reusing a thread for the next task, and the default runner does not reuse threads at all:
     * every body execution gets a fresh virtual thread, so {@code taskCount} on each of them is
     * always 1 and nothing can ever be inherited. On platform threads the runner really does
     * reuse eight of them across the rounds. See issue #352.
     *
     * <p>The read comes <em>before</em> the write. Contamination is a task reading a value it did
     * not set. This demonstration used to call {@code startRequest()} first and read afterwards,
     * which reads its own value every time, in the same task, and reports nothing however often
     * the thread is reused. See issue #346.
     */
    @Disabled("Remove @Disabled to see bug detected by ThreadLocalContaminationDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, useVirtualThreads = false,
            detectThreadLocalContamination = true, failOn = FailOn.LOW)
    void test_concurrent_detectsContamination() {
        Thread thread = Thread.currentThread();
        ThreadLocalContaminationDetector monitor =
                AsyncTestContext.threadLocalContaminationMonitor();
        service.observeContext(
                () -> monitor.recordSet(thread, RequestScopedService.REQUEST_ID, "REQUEST_ID"),
                value -> monitor.recordGet(thread, RequestScopedService.REQUEST_ID,
                        "REQUEST_ID", value != null));

        monitor.recordNewTask(thread, "process-" + thread.threadId());

        // A downstream component reads the request context without establishing it, which is
        // what downstream components do. On a fresh thread this is null. On a reused one it is
        // the previous request's id, and nobody notices.
        service.getCurrentId();

        service.startRequest("req-" + thread.threadId() + "-" + System.nanoTime());
        service.processRequest();
        service.endRequest();   // BUG: no remove(), so the id waits for the next task
    }
}
