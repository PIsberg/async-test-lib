package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ConnectionHandlerService;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Demonstrates {@code ThreadLeakDetector}.
 *
 * <p>The passing tests show normal single-threaded usage. The disabled test
 * shows the leak: every concurrent invocation spawns a handler thread and
 * registers it with the detector, but {@code shutdown()} is never called so
 * all threads remain alive when the detector analyzes at test end.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class ConnectionHandlerServiceTest {

    private ConnectionHandlerService service;

    @BeforeEach
    void setUp() {
        service = new ConnectionHandlerService();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // In the passing tests we do clean up; in the @Disabled async test we don't.
        service.shutdown();
    }

    @Test
    void test_singleThread_handlesConnection() {
        // Verify the service is constructed and a connection can be handled.
        assertNotNull(service);
        service.handleConnection("single-001");
        // tearDown will call shutdown() and join the thread.
    }

    @Test
    void test_singleThread_multipleConnections() {
        service.handleConnection("conn-A");
        service.handleConnection("conn-B");
        // Both threads are cleaned up in tearDown.
        assertNotNull(service.getConnectionThreads());
    }

    /**
     * Remove {@code @Disabled} to see {@code ThreadLeakDetector} report leaked
     * threads. Each invocation calls {@code handleConnection()}, records the
     * new thread with the detector, and then returns — without ever calling
     * {@code shutdown()}. By analysis time the threads are still alive.
     */
    @Disabled("Remove @Disabled to see bug detected by ThreadLeakDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectThreadLeaks = true)
    void test_concurrent_detectsThreadLeak() {
        String connId = "conn-" + Thread.currentThread().threadId();
        service.handleConnection(connId);

        // Register the leaked thread with the detector so it can track it.
        for (Thread t : service.getConnectionThreads()) {
            if (t.isAlive() && t.getName().contains(connId)) {
                AsyncTestContext.threadLeakDetector()
                        .recordThreadStart(t, t.getName());
            }
        }
        // BUG: shutdown() is never called — threads stay alive after the test.
    }
}
