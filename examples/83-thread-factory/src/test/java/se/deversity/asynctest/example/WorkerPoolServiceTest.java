package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.WorkerPoolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for WorkerPoolService.
 *
 * ========================================================================
 * DETECTOR: ThreadFactoryDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (tasks complete, functional correctness is fine)
 * - The same scenario with @AsyncTest FAILS (factory quality issues flagged)
 *
 * THE BUG:
 * WorkerPoolService uses Executors.newFixedThreadPool(4) with no ThreadFactory.
 * The default factory produces threads with generic names, non-daemon status,
 * and no UncaughtExceptionHandler — all operational quality problems.
 *
 * WHY @Test PASSES:
 * Functional correctness is unaffected. Tasks run and complete correctly.
 * Thread naming and daemon-status issues are invisible to functional assertions.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * ThreadFactoryDetector.registerFactory() and recordThreadCreated() capture the
 * factory and each thread it produces. analyze() checks thread names against a
 * pattern, daemon status, and exception handler presence — reporting all missing
 * best practices in the ThreadFactoryReport.
 *
 * DETECTORS TRIGGERED:
 *   ThreadFactoryDetector — primary: identifies unnamed, non-daemon, handler-less threads
 *
 * FIX: provide a named, daemon ThreadFactory with an UncaughtExceptionHandler.
 */
class WorkerPoolServiceTest {

    private WorkerPoolService service;

    @BeforeEach
    void setUp() {
        service = new WorkerPoolService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, always correct
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_submitTask_completes() throws Exception {
        var future = service.submit(() -> { /* no-op */ });
        future.get();
        assertNotNull(future);
    }

    @Test
    void test_singleThread_poolIsNotNull() {
        assertNotNull(service.getPool());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes default-factory thread quality issues
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see default-factory issues detected by ThreadFactoryDetector")
    @AsyncTest(threads = 8, invocations = 30, detectAll = false, detectThreadFactoryIssues = true, failOn = FailOn.LOW)
    void test_concurrent_detectsDefaultFactory() throws Exception {
        var detector = AsyncTestContext.get().threadFactoryMonitor();

        // Wrap the pool's default factory so we can observe threads it creates.
        // Executors.defaultThreadFactory() is the factory used internally.
        ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        detector.registerFactory(defaultFactory, "worker-pool-factory");

        // Submit a task that records the thread the factory created.
        var future = service.submit(() -> {
            Thread worker = Thread.currentThread();
            detector.recordThreadCreated(defaultFactory, "worker-pool-factory", worker);
        });
        future.get();
    }

    // Expose Executors in this file's scope for clarity.
    private static final class Executors {
        static ThreadFactory defaultThreadFactory() {
            return java.util.concurrent.Executors.defaultThreadFactory();
        }
    }
}
