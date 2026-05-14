package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.diagnostics.BusyWaitDetector;
import se.deversity.asynctest.example.service.SpinPollingWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SpinPollingWorker.
 *
 * ========================================================================
 * DETECTOR: BusyWaitDetector
 * ========================================================================
 *
 * This test demonstrates a common performance anti-pattern where:
 * - A sequential @Test PASSES (correct results, no spin visible)
 * - The same test with @AsyncTest + BusyWaitDetector reveals the
 *   CPU-intensive spin loop created by tight polling
 *
 * THE BUG:
 * SpinPollingWorker processes tasks by spinning in a tight while loop:
 *
 *     while (!taskQueue.isEmpty()) {
 *         result = taskQueue.poll();
 *     }
 *
 * Under concurrent load with multiple threads all calling process():
 *   - Each thread burns 100% of its CPU slice polling an empty queue
 *   - No CPU is released to threads that have real work to do
 *   - Latency spikes across the entire application
 *   - BusyWaitDetector reports spin iterations exceeding the threshold
 *
 * WHY @Test PASSES:
 * Single-threaded access finishes the queue quickly and returns. There
 * are no other threads to starve, so the spin is invisible.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * With many threads all calling processInstrumented() simultaneously and
 * each iteration calling detector.recordLoopIteration(), the detector
 * accumulates spin counts across threads. Once any thread exceeds
 * SPIN_THRESHOLD_ITERATIONS (10,000) the loop is flagged as busy-waiting.
 * The @AfterEach assertion verifies that the detector found issues.
 *
 * DETECTORS TRIGGERED:
 * BusyWaitDetector — Primary: tight polling loop without yielding
 *
 * FIX:
 * - Replace ConcurrentLinkedQueue + spin with LinkedBlockingQueue.take()
 * - Or use wait()/notify() inside a synchronized block to park idle threads
 */
class SpinPollingWorkerTest {

    private SpinPollingWorker worker;
    private BusyWaitDetector busyWaitDetector;
    // Flag to guard the @AfterEach assertion so it only runs after the
    // @AsyncTest invocation (not after the plain @Test methods).
    private volatile boolean runningAsyncTest = false;

    @BeforeEach
    void setUp() {
        worker = new SpinPollingWorker();
        busyWaitDetector = new BusyWaitDetector();

        // Pre-load enough tasks that at least one thread iterates past the
        // BusyWaitDetector spin threshold (10,000 iterations) in a single burst.
        for (int i = 0; i < 20_000; i++) {
            worker.submit("task-" + i);
        }
    }

    /**
     * After the @AsyncTest run completes, verify the detector captured the
     * spin loop. @AfterEach runs once after all threads and invocations finish.
     */
    @AfterEach
    void verifyBusyWaitDetected() {
        if (!runningAsyncTest) {
            return;
        }
        BusyWaitDetector.BusyWaitReport report = busyWaitDetector.analyzeBusyWaiting();
        assertTrue(report.hasIssues(),
                "BusyWaitDetector should have flagged the spin loop.\n" + report);
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, no spin visible
    // -------------------------------------------------------------------------

    @Test
    void testProcess_singleThread_drainsQueue() {
        // Drain the queue sequentially: all tasks are consumed, no spin pressure.
        worker.process();
        // After draining, further calls return null (queue is empty)
        assertNull(worker.process());
    }

    @Test
    void testProcess_emptyQueue_returnsNull() {
        SpinPollingWorker emptyWorker = new SpinPollingWorker();
        assertNull(emptyWorker.process());
    }

    @Test
    void testSubmitThenProcess_singleThread_returnsResult() {
        SpinPollingWorker fresh = new SpinPollingWorker();
        fresh.submit("hello");
        fresh.submit("world");
        String result = fresh.process();
        assertNotNull(result);
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the busy-wait spin loop
    // -------------------------------------------------------------------------

    /**
     * The bug: with many threads all calling processInstrumented() the tight
     * poll loop drives recordLoopIteration() past the spin threshold.
     * BusyWaitDetector captures the spin events and the @AfterEach assertion
     * verifies the detection fired.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — @AfterEach will assert that BusyWaitDetector
     *    flagged the spin loop
     * 3. Fix: replace ConcurrentLinkedQueue + spin with LinkedBlockingQueue.take()
     */
    @Disabled("Remove @Disabled to see busy-waiting detected by BusyWaitDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectBusyWaiting = true)
    void testProcess_concurrent_detectsBusyWaiting() {
        runningAsyncTest = true;
        // Each thread drains whatever is left in the shared queue via the
        // instrumented path, recording every loop iteration for the detector.
        // The first thread(s) to run will process many thousands of tasks,
        // driving the iteration count past the 10,000-iteration spin threshold.
        worker.processInstrumented(
                busyWaitDetector::recordLoopIteration,
                busyWaitDetector::recordYield);
    }

    /**
     * Fixed version: LinkedBlockingQueue with take() parks the thread at zero
     * CPU cost while the queue is empty. No spin — no BusyWaitDetector alert.
     */
    @Test
    void testProcess_fixedWithBlockingQueue_singleThread() throws InterruptedException {
        java.util.concurrent.LinkedBlockingQueue<String> blockingQueue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        blockingQueue.put("task-A");
        blockingQueue.put("task-B");

        // take() blocks until an element is available — zero-CPU wait
        String first = blockingQueue.take();
        String second = blockingQueue.take();

        assertEquals("task-A", first);
        assertEquals("task-B", second);
    }
}
