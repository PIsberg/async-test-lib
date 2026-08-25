package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.TaskRunnerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TaskRunnerService.
 *
 * ========================================================================
 * DETECTOR: ExecutorShutdownDetector
 * ========================================================================
 *
 * THE BUG:
 * TaskRunnerService creates a FixedThreadPool(4) in its constructor but never
 * calls shutdown(). Each service instance leaks four threads. Under repeated
 * concurrent test invocations the thread count grows without bound.
 *
 * WHY @Test PASSES:
 * A single-threaded test submits one task and exits. The leak is invisible
 * because the JVM does not exit between tests and the threads simply idle.
 *
 * WHY @AsyncTest DETECTS:
 * ExecutorShutdownDetector.recordExecutorCreated() is called when the executor
 * is registered. After all invocations recordShutdownCalled() has never been
 * invoked for that executor, so the detector reports the missing shutdown.
 *
 * FIX:
 * Implement AutoCloseable on TaskRunnerService, call executor.shutdown()
 * followed by executor.awaitTermination() in close(), and use try-with-resources
 * in the caller.
 */
class TaskRunnerServiceTest {

    private TaskRunnerService service;

    @BeforeEach
    void setUp() {
        service = new TaskRunnerService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testRunTask_singleThread_taskExecutes() throws Exception {
        boolean[] ran = {false};
        Future<?> f = service.runTask(() -> ran[0] = true);
        f.get(2, TimeUnit.SECONDS);
        assertTrue(ran[0], "Task should have executed");
    }

    @Test
    void testRunTask_multipleTasksSequentially_allComplete() throws Exception {
        int[] counter = {0};
        Future<?> f1 = service.runTask(() -> counter[0]++);
        Future<?> f2 = service.runTask(() -> counter[0]++);
        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);
        assertEquals(2, counter[0]);
    }

    @Test
    void testGetExecutor_notShutDown_confirmsLeak() {
        // Explicitly demonstrate the bug: executor is running but never shut down
        assertFalse(service.getExecutor().isShutdown(),
                "Executor is never shut down — the thread leak bug");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 threads each creating a TaskRunnerService and submitting tasks,
     * executors accumulate without being shut down. ExecutorShutdownDetector
     * records each executor and reports those that never received a shutdown call.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: implement AutoCloseable and call executor.shutdown() in close()
     */
    @Disabled("Remove @Disabled to see the bug detected by ExecutorShutdownDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectExecutorShutdown = true, failOn = FailOn.LOW)
    void testRunTask_concurrent_detectsMissingShutdown() {
        // Register the executor with the detector
        AsyncTestContext.executorShutdownMonitor()
                .recordExecutorCreated(service.getExecutor(), "task-runner-pool");

        // Submit a task — detector tracks that work was done
        AsyncTestContext.executorShutdownMonitor()
                .recordTaskSubmitted(service.getExecutor());

        service.runTask(() -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) sum += i;
            if (sum < 0) throw new RuntimeException("unreachable");
        });

        // shutdown() is intentionally never called — detector will report this
    }
}
