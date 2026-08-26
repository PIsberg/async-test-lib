package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.PriorityQueueService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** Submission timestamps by task id, so a task's wait can be timed from both ends. */
    private final Map<String, Long> submittedAt = new ConcurrentHashMap<>();

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
    @AsyncTest(threads = 8, invocations = 2, detectAll = false,
            detectThreadStarvation = true, failOn = FailOn.LOW)

    void test_concurrent_detectsStarvation() {
        var detector = AsyncTestContext.threadStarvationDetector();
        detector.registerExecutor(service.getPool(), "priority-pool", 4);
        detector.setStarvationThresholdMs(10);

        // The wait is timed from both ends. recordTaskStart compares now against the submission
        // timestamp, so calling it from here - which is what this demonstration used to do,
        // immediately after recordTaskSubmission - always measured about zero and never crossed
        // the threshold. The other end is inside the pool thread, after it finally gets the
        // shared lock, which is where the starvation actually happens. See issue #363.
        service.observeTaskPhases(
                taskId -> {
                    Long submitted = submittedAt.get(taskId);
                    if (submitted != null) {
                        detector.recordTaskStart("priority-pool", submitted);
                    }
                },
                taskId -> detector.recordTaskEnd("priority-pool"));

        String taskId = "task-" + Thread.currentThread().threadId() + "-" + System.nanoTime();
        submittedAt.put(taskId, detector.recordTaskSubmission(service.getPool()));

        // Eight threads submit into a four-thread pool whose tasks each hold one non-fair
        // monitor, for 20ms or 5ms. Whoever is fifth in that queue waits far past the 10ms
        // threshold, and the queue is what the detector reports.
        Future<?> task = Thread.currentThread().threadId() % 2 == 0
                ? service.submitHighPriority(taskId)
                : service.submitLowPriority(taskId);

        // Bounded, and absorbed. The task has to be allowed to run before analysis or there is
        // nothing to report, but an unbounded get() is what made this round time out before the
        // detector was ever consulted.
        try {
            task.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException stillQueued) {
            // Starved past the wait, which is the finding rather than a failure.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException failed) {
            // The task threw; the recorded wait still stands.
        }
    }
}
