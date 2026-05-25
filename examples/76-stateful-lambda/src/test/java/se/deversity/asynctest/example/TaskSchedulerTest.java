package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.TaskScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TaskScheduler.
 *
 * ========================================================================
 * DETECTOR: StatefulLambdaDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (single thread, no contention on the array)
 * - The same scenario with @AsyncTest FAILS (lost updates on shared int[])
 *
 * THE BUG:
 * TaskScheduler.scheduleCountingTasks() captures a single int[] array in a
 * lambda and submits that lambda (or equivalent closures sharing the array) to
 * n threads. count[0]++ is a non-atomic read-modify-write; under concurrency
 * multiple threads read the same value and write back N+1, losing updates.
 *
 * WHY @Test PASSES:
 * With a single thread, count[0]++ executes sequentially with no interleaving.
 * The final count equals n — no updates are lost.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * StatefulLambdaDetector.recordExecution() tracks which threads run the same
 * lambda, and recordCapturedMutation() records every write to a captured
 * variable. When multiple threads appear in the execution set and mutations are
 * recorded, the analysis flags a shared-mutable-capture race.
 *
 * DETECTORS TRIGGERED:
 *   StatefulLambdaDetector — primary: captures concurrent mutation of lambda-captured state
 *
 * FIX: replace int[] with AtomicInteger; or give each submitted task its own
 *      independent counter so threads never share mutable state.
 */
class TaskSchedulerTest {

    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TaskScheduler();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, always correct
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_schedulerIsCreated() {
        assertNotNull(scheduler);
    }

    @Test
    void test_singleThread_submitsTasks() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var futures = scheduler.scheduleCountingTasks(pool, 3);
        assertEquals(3, futures.size());
        for (var f : futures) f.get();
        pool.shutdown();
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes shared mutable lambda capture
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see shared mutable lambda capture detected by StatefulLambdaDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectStatefulLambda = true)
    void test_concurrent_detectsStatefulLambda() {
        var detector = AsyncTestContext.get().statefulLambdaDetector();

        // Simulate the buggy pattern: a single shared int[] captured by a Runnable.
        // Use an array-wrapper to allow self-reference from inside the lambda.
        int[] count = {0};
        Runnable[] taskRef = {null};
        taskRef[0] = () -> {
            detector.recordExecution(taskRef[0], "counting-task", Thread.currentThread());
            detector.recordCapturedMutation(taskRef[0], "count", Thread.currentThread());
            count[0]++; // BUG: non-atomic update on shared captured array element
        };

        taskRef[0].run();
    }
}
