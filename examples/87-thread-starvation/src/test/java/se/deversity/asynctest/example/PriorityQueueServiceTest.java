package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.PriorityQueueService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates {@code ThreadStarvationDetector}.
 *
 * <p>The passing tests confirm the service can be constructed and submit tasks.
 * The disabled test exposes starvation: under concurrent load, high-priority
 * tasks hold the non-fair lock for long durations, and low-priority tasks
 * queue for far longer than the detector's starvation threshold.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class PriorityQueueServiceTest {

    private PriorityQueueService service;

    @BeforeEach
    void setUp() {
        service = new PriorityQueueService();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        service.shutdown();
    }

    @Test
    void test_singleThread_highPriorityCompletes() throws Exception {
        var future = service.submitHighPriority("hp-1");
        future.get();
        assertTrue(service.getProcessedCount() >= 1);
    }

    @Test
    void test_singleThread_lowPriorityCompletes() throws Exception {
        var future = service.submitLowPriority("lp-1");
        future.get();
        assertNotNull(service.getPool());
    }

    /**
     * Remove {@code @Disabled} to see {@code ThreadStarvationDetector} report
     * tasks that waited beyond the configured threshold.
     *
     * <p>The test registers the pool, records task submission and start/end
     * events. High-priority tasks hold the lock for 20 ms; the detector's
     * default threshold is typically much lower, so low-priority tasks are
     * reported as starved.
     */
    @Disabled("Remove @Disabled to see bug detected by ThreadStarvationDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectThreadStarvation = true, failOn = FailOn.LOW)
    void test_concurrent_detectsStarvation() {
        var detector = AsyncTestContext.threadStarvationDetector();

        // Register the pool with a known size.
        detector.registerExecutor(service.getPool(), "priority-pool", 4);
        detector.setStarvationThresholdMs(10);

        long submitTime = detector.recordTaskSubmission(service.getPool());

        // Submit a mix of high and low priority tasks.
        try {
            if (Thread.currentThread().threadId() % 2 == 0) {
                detector.recordTaskStart("priority-pool", submitTime);
                service.submitHighPriority("hp-" + Thread.currentThread().threadId()).get();
                detector.recordTaskEnd("priority-pool");
            } else {
                detector.recordTaskStart("priority-pool", submitTime);
                service.submitLowPriority("lp-" + Thread.currentThread().threadId()).get();
                detector.recordTaskEnd("priority-pool");
            }
        } catch (Exception ignored) {
            // Pool may be saturated — expected under stress.
        }
    }
}
