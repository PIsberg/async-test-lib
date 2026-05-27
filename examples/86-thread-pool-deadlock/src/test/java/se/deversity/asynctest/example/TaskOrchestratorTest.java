package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.TaskOrchestrator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates {@code ThreadPoolDeadlockDetector}.
 *
 * <p>The passing tests show single-threaded orchestration works. The disabled
 * test reveals the pool-deadlock pattern: under 8 concurrent test threads each
 * calling {@code orchestrate()}, the 2-thread pool quickly fills with
 * blocking task-A instances leaving no thread available for task-B.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class TaskOrchestratorTest {

    private TaskOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new TaskOrchestrator();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        orchestrator.shutdown();
    }

    @Test
    void test_singleThread_orchestratesSuccessfully() throws Exception {
        // With only one caller the pool has a free thread for the nested task.
        String result = orchestrator.orchestrate("hello");
        assertNotNull(result);
        assertTrue(result.contains("HELLO"));
    }

    @Test
    void test_singleThread_poolIsNotNull() {
        assertNotNull(orchestrator.getPool());
    }

    /**
     * Remove {@code @Disabled} to see {@code ThreadPoolDeadlockDetector} report
     * nested submissions to the same pool.
     *
     * <p>The detector is registered with the pool before calling
     * {@code orchestrate()}. Inside {@code orchestrate()} task A submits task B
     * to the same pool and calls {@code recordNestedSubmission()} — this is
     * the signal the detector uses to identify the deadlock pattern when active
     * tasks equal the pool size.
     */
    @Disabled("Remove @Disabled to see bug detected by ThreadPoolDeadlockDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectThreadPoolDeadlocks = true)
    void test_concurrent_detectsDeadlockRisk() {
        var detector = AsyncTestContext.threadPoolDeadlockDetector();

        // Register the pool so the detector knows its size.
        detector.registerPool(orchestrator.getPool(), "orchestrator-pool");

        // Record that the current (outer) task is active.
        detector.recordNestedSubmission(orchestrator.getPool(), "orchestrator-pool");

        try {
            // This call will block inside the pool: classic nested-submission deadlock.
            orchestrator.orchestrate("input-" + Thread.currentThread().threadId());
        } catch (Exception ignored) {
            // Deadlock or timeout — expected under stress.
        } finally {
            detector.recordTaskCompleted(orchestrator.getPool());
        }
    }
}
