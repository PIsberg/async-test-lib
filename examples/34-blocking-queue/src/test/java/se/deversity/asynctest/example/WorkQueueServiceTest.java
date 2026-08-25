package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.WorkQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for WorkQueueService.
 *
 * ========================================================================
 * DETECTOR: BlockingQueueDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * WorkQueueService uses offer() and poll() on an ArrayBlockingQueue(5).
 * offer() returns false silently when the queue is full — tasks are dropped.
 * poll() returns null when the queue is empty — toUpperCase() throws NPE.
 *
 * WHY @Test PASSES:
 * Single-threaded execution rarely fills the queue (capacity 5 is sufficient
 * for sequential submit/process pairs). poll() always finds an item because
 * we just submitted one. No drops, no NPE.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads all calling submitTask() at once saturate the queue immediately.
 * Concurrent producers see offer() return false (dropped tasks).
 * Concurrent consumers race ahead of producers and call processNext() on an
 * empty queue — poll() returns null and toUpperCase() throws NPE.
 * BlockingQueueDetector records every offer failure and poll-null event.
 *
 * DETECTORS TRIGGERED:
 *   BlockingQueueDetector — primary: tracks offer/poll failures on bounded queues
 *
 * FIX: replace offer() with put() and poll() with take() (or null-guarded
 *      poll(timeout, unit)).
 */
class WorkQueueServiceTest {

    private WorkQueueService service;

    @BeforeEach
    void setUp() {
        service = new WorkQueueService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testSubmitAndProcess_singleThread_works() {
        boolean accepted = service.submitTask("hello");
        assertTrue(accepted, "Queue should accept the first task");
        String result = service.processNext();
        assertEquals("HELLO", result);
    }

    @Test
    void testPendingCount_afterSubmit() {
        service.submitTask("task-a");
        service.submitTask("task-b");
        assertEquals(2, service.pendingCount());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes offer/poll misuse under concurrency
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see blocking-queue drops detected by BlockingQueueDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectBlockingQueueIssues = true, failOn = FailOn.LOW)
    void testWorkQueue_concurrent_detectsDrops() {
        var queue = service.getQueue();

        // Register the queue once per invocation (detector deduplicates by identity)
        AsyncTestContext.get().blockingQueueMonitor()
                .registerQueue(queue, "work-queue", 5);

        // Producer path
        String task = "task-" + Thread.currentThread().getName();
        boolean accepted = service.submitTask(task);
        AsyncTestContext.get().blockingQueueMonitor()
                .recordOffer(queue, "work-queue", accepted);

        // Consumer path — poll() may return null when queue is empty
        if (!queue.isEmpty()) {
            String result = queue.poll();
            boolean got = result != null;
            AsyncTestContext.get().blockingQueueMonitor()
                    .recordPoll(queue, "work-queue", got);
            // Intentionally call the buggy service method only when we know
            // an item was available, to show the NPE path exists at all:
            if (got) {
                assertNotNull(result.toUpperCase());
            }
        }
    }
}
