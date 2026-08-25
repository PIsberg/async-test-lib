package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.example.service.WorkQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;

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
 * offer() returns false when the queue is full and the caller ignores it, so
 * tasks are dropped. poll() returns null when the queue is empty, and
 * processNext() calls toUpperCase() on it, so an empty queue is an NPE.
 *
 * WHY @Test PASSES:
 * Single-threaded execution rarely fills the queue (capacity 5 is sufficient
 * for sequential submit/process pairs). poll() always finds an item because
 * we just submitted one. No drops, no NPE.
 *
 * WHAT THE DETECTOR WILL AND WILL NOT SAY:
 * A rejected offer() is not a finding on its own, and this example used to
 * promise that it was. `if (!q.offer(x)) retryLater(x);` is what correct
 * backpressure looks like, and reporting it flagged every correct bounded
 * queue, so BlockingQueueDetector counts rejections without gating on them.
 * What it does gate on is saturation: a queue sitting at its bound. That is the
 * shape that says the sizing or the drain rate is wrong, and it is what this
 * demonstration produces. The rejection count is still in the report, next to
 * it. See issue #346.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads all calling submitTask() at once fill the queue immediately, and
 * nothing on this path drains it, so it stays at 5/5 for the rest of the run.
 * BlockingQueueDetector reports the saturation and counts the 395 rejected
 * offers alongside it.
 *
 * DETECTOR ENABLED HERE:
 *   BlockingQueueDetector — a bounded queue held at its capacity. It is the only
 *   one this demonstration switches on, so it is the only one that can report.
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

    /**
     * The other half of the bug, and it needs no detector at all: poll() returns null on an
     * empty queue and processNext() calls toUpperCase() on it.
     */
    @Test
    void testProcessNext_emptyQueue_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> service.processNext(),
                "poll() returns null on an empty queue and processNext() does not guard it");
    }

    /**
     * The detector's positive direction: a queue sitting at its bound is the saturation it
     * gates on.
     */
    @Test
    void testBlockingQueueDetector_queueAtItsBound_reports() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = service.getQueue();
        detector.registerQueue(queue, "work-queue", service.capacity());

        for (int i = 0; i < service.capacity() + 2; i++) {
            detector.recordOffer(queue, "work-queue", service.submitTask("task-" + i));
        }

        assertTrue(detector.analyze().hasIssues(),
                "a queue held at capacity is the finding this detector still gates on");
    }

    /**
     * And the other direction. A rejected offer() is not a finding on its own: checking the
     * return value is what correct backpressure looks like, and a detector that reported it
     * would flag every correct bounded queue. Here the queue never approaches its bound, so
     * there is nothing to say.
     */
    @Test
    void testBlockingQueueDetector_queueWellBelowItsBound_isSilent() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = service.getQueue();
        detector.registerQueue(queue, "work-queue", service.capacity());

        detector.recordOffer(queue, "work-queue", service.submitTask("only-one"));
        detector.recordPoll(queue, "work-queue", queue.poll() != null);

        assertFalse(detector.analyze().hasIssues(),
                "one item in and one item out is a queue doing its job");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes offer/poll misuse under concurrency
    // -----------------------------------------------------------------------

    /**
     * The bug: a fire-and-forget producer. Eight threads submit into a queue bounded at 5 and
     * nothing drains it, so after the first few offers every task is rejected and thrown away.
     * The queue sits at its bound for the rest of the run, which is the saturation
     * BlockingQueueDetector gates on.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      work-queue: queue reached 5/5 capacity (saturation risk)
     *    with the rejection count alongside it in the report
     * 3. Fix: use put() so producers block for space, or handle the false from offer()
     */
    @Disabled("Remove @Disabled to see queue saturation detected by BlockingQueueDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false,
            detectBlockingQueueIssues = true, failOn = FailOn.LOW)
    void testWorkQueue_concurrent_detectsSaturation() {
        BlockingQueue<String> queue = service.getQueue();
        BlockingQueueDetector monitor = AsyncTestContext.blockingQueueMonitor();

        // Registration is putIfAbsent, so every worker calling it registers the queue once.
        monitor.registerQueue(queue, "work-queue", service.capacity());

        // BUG: the return value is ignored by every real caller of submitTask, and nothing on
        // this path drains the queue. The task is simply gone.
        boolean accepted = service.submitTask("task-" + Thread.currentThread().threadId());
        monitor.recordOffer(queue, "work-queue", accepted);
    }
}
