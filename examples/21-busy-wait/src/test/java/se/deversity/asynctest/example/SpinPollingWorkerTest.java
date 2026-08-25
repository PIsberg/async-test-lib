package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.BusyWaitDetector;
import se.deversity.asynctest.example.service.SpinPollingWorker;
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
 * This test demonstrates a performance anti-pattern where:
 * - A sequential @Test PASSES (correct results, no spin visible)
 * - The same code under @AsyncTest reveals the CPU burned by tight polling
 *
 * THE BUG:
 * SpinPollingWorker.awaitTask() waits for the next task by polling in a tight loop
 * with no back-off:
 *
 *     for (long spins = 0; spins < maxSpins; spins++) {
 *         String task = taskQueue.poll();
 *         if (task != null) return task;
 *         // no onSpinWait, no yield, no park
 *     }
 *
 * With more workers than tasks, the workers that lose the race burn a core for the
 * whole budget while the winners do the real work.
 *
 * WHY @Test PASSES:
 * A single thread that submits a task and then asks for one always finds it on the
 * first poll. The loop exits after one iteration, far below the detector's threshold.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * Eight threads share four tasks. The four that come up empty spin their full budget,
 * recording an iteration each time, and BusyWaitDetector flags any thread whose
 * iteration count passed SPIN_THRESHOLD_ITERATIONS (10,000) before the loop exited.
 *
 * DETECTOR ENABLED HERE:
 * BusyWaitDetector — tight polling loop without yielding. It is the only one this
 * demonstration switches on, so it is the only one that can report.
 *
 * FIX:
 * - Replace ConcurrentLinkedQueue + spin with LinkedBlockingQueue.take()
 * - Or use wait()/notify() inside a synchronized block to park idle threads
 */
class SpinPollingWorkerTest {

    /** Comfortably past BusyWaitDetector's 10,000-iteration spin threshold. */
    private static final long SPIN_BUDGET = 25_000L;

    private SpinPollingWorker worker;

    @BeforeEach
    void setUp() {
        worker = new SpinPollingWorker();
        // Fewer tasks than the demonstration has threads, which is the whole point: the
        // workers that lose the race are the ones that spin.
        for (int i = 0; i < 4; i++) {
            worker.submit("task-" + i);
        }
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, no spin visible
    // -------------------------------------------------------------------------

    @Test
    void testProcess_singleThread_drainsQueue() {
        worker.process();
        assertNull(worker.process(), "queue is empty after a drain");
    }

    @Test
    void testProcess_emptyQueue_returnsNull() {
        assertNull(new SpinPollingWorker().process());
    }

    @Test
    void testAwaitTask_taskAvailable_returnsOnFirstPoll() {
        SpinPollingWorker fresh = new SpinPollingWorker();
        fresh.submit("hello");

        long[] iterations = {0};
        String claimed = fresh.awaitTask(SPIN_BUDGET, () -> iterations[0]++, () -> { });

        assertEquals("hello", claimed);
        assertEquals(1, iterations[0], "a waiting task is found on the first poll, so there is no spin");
    }

    @Test
    void testAwaitTask_emptyQueue_spinsTheWholeBudget() {
        SpinPollingWorker empty = new SpinPollingWorker();

        long[] iterations = {0};
        String claimed = empty.awaitTask(SPIN_BUDGET, () -> iterations[0]++, () -> { });

        assertNull(claimed);
        assertEquals(SPIN_BUDGET, iterations[0],
                "this is the bug: " + SPIN_BUDGET + " polls of an empty queue, no back-off");
    }

    /**
     * The single-threaded spin is real, but a BusyWaitDetector reading it alone still
     * reports it: the threshold is about iteration count, not about thread count. This
     * pins the detector's positive direction without needing the concurrent run.
     */
    @Test
    void testAwaitTask_spinIsVisibleToTheDetector() {
        BusyWaitDetector detector = new BusyWaitDetector();
        new SpinPollingWorker().awaitTask(
                SPIN_BUDGET, detector::recordLoopIteration, detector::recordYield);

        assertTrue(detector.analyzeBusyWaiting().hasIssues(),
                "a " + SPIN_BUDGET + "-iteration spin is past the 10,000 threshold");
    }

    /**
     * The other direction: a loop that finds its task immediately must stay silent, or the
     * detector would flag every loop in the program.
     */
    @Test
    void testAwaitTask_noSpin_isSilent() {
        BusyWaitDetector detector = new BusyWaitDetector();
        SpinPollingWorker fresh = new SpinPollingWorker();
        fresh.submit("hello");
        fresh.awaitTask(SPIN_BUDGET, detector::recordLoopIteration, detector::recordYield);

        assertFalse(detector.analyzeBusyWaiting().hasIssues(),
                "one poll that found work is not a busy-wait");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the busy-wait spin loop
    // -------------------------------------------------------------------------

    /**
     * The bug: eight threads compete for four tasks, so at least four of them poll an empty
     * queue for the whole budget. BusyWaitDetector reports the threads that spun past the
     * threshold, and failOn = LOW turns that finding into a failed run.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with a BusyWaitDetector finding naming the spinning threads
     * 3. Fix: replace ConcurrentLinkedQueue + spin with LinkedBlockingQueue.take()
     */
    @Disabled("Remove @Disabled to see busy-waiting detected by BusyWaitDetector")
    @AsyncTest(threads = 8, invocations = 5, detectAll = false, detectBusyWaiting = true, failOn = FailOn.LOW)
    void testProcess_concurrent_detectsBusyWaiting() {
        // The detector has to be the one the run owns. This demonstration used to record into a
        // locally constructed BusyWaitDetector, which the library never reads, so failOn had
        // nothing to gate on however hard the threads spun. See issue #346.
        BusyWaitDetector detector = AsyncTestContext.busyWaitDetector();

        worker.awaitTask(SPIN_BUDGET, detector::recordLoopIteration, detector::recordYield);
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
