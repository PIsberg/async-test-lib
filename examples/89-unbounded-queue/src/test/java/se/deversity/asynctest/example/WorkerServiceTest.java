package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.WorkerService;

import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Demonstrates {@code UnboundedQueueDetector}.
 *
 * <p>The passing tests show the service accepts and processes tasks. The
 * disabled test exposes the bug: with 8 threads each submitting tasks per
 * invocation, the internal unbounded queue accumulates work faster than the
 * 2-thread pool can drain it.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class WorkerServiceTest {

    private WorkerService service;

    @BeforeEach
    void setUp() {
        service = new WorkerService();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        service.shutdown();
    }

    @Test
    void test_singleThread_submitTask() {
        assertNotNull(service.submit("task-1"));
    }

    @Test
    void test_singleThread_poolIsNotNull() {
        assertNotNull(service.getPool());
    }

    /**
     * Remove {@code @Disabled} to see {@code UnboundedQueueDetector} report an
     * unbounded queue that grows without backpressure.
     *
     * <p>An explicit {@link LinkedBlockingQueue} with {@link Integer#MAX_VALUE}
     * capacity is registered with the detector to mirror the internal queue that
     * {@code Executors.newFixedThreadPool} creates. Every {@code submit()} call
     * enqueues work on that queue with no dequeue backpressure.
     */
    @Disabled("Remove @Disabled to see bug detected by UnboundedQueueDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectUnboundedQueue = true)
    void test_concurrent_detectsUnboundedQueue() {
        var detector = AsyncTestContext.unboundedQueueDetector();

        // Mirror the internal unbounded queue that newFixedThreadPool creates.
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(); // Integer.MAX_VALUE capacity

        // Register the queue so the detector can track it.
        detector.recordQueueCreation(queue, "worker-pool-queue", Integer.MAX_VALUE);

        // Record the enqueue event (submit side).
        detector.recordEnqueue(queue);

        // Submit work — no backpressure; queue grows unboundedly under load.
        service.submit("task-" + Thread.currentThread().threadId());
    }
}
