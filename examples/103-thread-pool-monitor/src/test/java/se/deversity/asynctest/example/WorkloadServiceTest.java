package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.WorkloadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for WorkloadService.
 *
 * ========================================================================
 * DETECTOR: ThreadPoolMonitor
 * ========================================================================
 *
 * THE BUG:
 * WorkloadService uses a fixed thread pool of only 2 threads. Under concurrent
 * load from 8 test threads each submitting tasks, both pool threads are always
 * busy. New submissions queue up indefinitely, response latency grows, and the
 * active thread count stays pinned at the maximum (2/2). If the pool had a
 * bounded queue, tasks would be rejected.
 *
 * WHY @Test PASSES:
 * Single-threaded tests submit a small number of tasks sequentially. The pool
 * can always service them before the next submission, so the queue never grows.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 concurrent test threads each submitting a task, ThreadPoolMonitor records
 * submissions, starts, and completions. It reports thread starvation (active ==
 * maxSize), queue near capacity, and long-running tasks blocking the pool.
 *
 * FIX:
 * Use Runtime.getRuntime().availableProcessors() or virtual threads for I/O-bound work.
 */
class WorkloadServiceTest {

    private WorkloadService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testSubmitWork_taskIsExecuted() throws Exception {
        int[] result = {0};
        Future<?> f = service.submitWork(() -> result[0] = 42);
        f.get();
        assertEquals(42, result[0]);
    }

    @Test
    void testGetPool_returnsNonNull() {
        assertNotNull(service.getPool());
    }

    @Test
    void testGetActiveCount_initiallyZero() {
        assertEquals(0, service.getActiveCount());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the pool saturation
    // -------------------------------------------------------------------------

    /**
     * Eight threads each submit a slow task. ThreadPoolMonitor records each
     * submission, start, and completion and reports that both threads are always
     * busy (starvation), the queue peak is high, and tasks are delayed.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: increase pool size or use virtual threads
     */
    @Disabled("Remove @Disabled to see the bug detected by ThreadPoolMonitor")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, monitorThreadPool = true, failOn = FailOn.LOW)
    void test_concurrent_detectsPoolSaturation() {
        var mon = AsyncTestContext.threadPoolMonitor();
        var pool = service.getPool();
        var tpe = (ThreadPoolExecutor) pool;

        // Register the pool: 2 core threads, 2 max threads, unbounded queue (Integer.MAX_VALUE)
        mon.registerPool(pool, "workload-pool", 2, 2, Integer.MAX_VALUE);

        mon.recordTaskSubmitted(pool);
        long start = System.currentTimeMillis();

        Future<?> f = service.submitWork(() -> {
            mon.recordTaskStarted(pool);
            try {
                // Simulate slow I/O-bound work that blocks the pool thread
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long duration = System.currentTimeMillis() - start;
            mon.recordTaskCompleted(pool, duration);
        });

        // Check if all threads are busy (starvation condition)
        if (tpe.getActiveCount() >= tpe.getMaximumPoolSize()) {
            mon.recordTaskRejected(pool,
                "Pool saturated: " + tpe.getActiveCount() + "/" + tpe.getMaximumPoolSize()
                + " threads busy, queue depth=" + tpe.getQueue().size());
        }
    }
}
