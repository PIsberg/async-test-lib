package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.RecursiveCounter;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RecursiveCounter.
 *
 * ========================================================================
 * DETECTOR: ForkJoinTaskBlockingDetector
 * ========================================================================
 *
 * THE BUG:
 * RecursiveCounter.compute() calls Thread.sleep(1) inside the leaf case to
 * simulate latency. ForkJoin worker threads must never block; a sleeping worker
 * cannot steal pending tasks from the deque, causing the pool to become
 * unresponsive and throughput to collapse under load.
 *
 * WHY @Test PASSES:
 * Single-threaded invocation of compute() on the calling thread adds a small
 * delay and produces the correct sum. No contention occurs.
 *
 * WHY @AsyncTest DETECTS:
 * ForkJoinTaskBlockingDetector.recordForkJoinTaskEntered() marks the thread as
 * inside a ForkJoin task. recordBlockingCallAttempted() is then called when
 * Thread.sleep() is detected on that thread, which the detector flags as a
 * violation.
 *
 * FIX:
 * Replace Thread.sleep() with ForkJoinPool.managedBlock() so the pool can
 * compensate with an additional temporary worker, or remove the blocking
 * call entirely.
 */
class RecursiveCounterTest {

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testCompute_smallRange_returnsCorrectSum() {
        RecursiveCounter counter = new RecursiveCounter(1, 10);
        long result = new ForkJoinPool().invoke(counter);
        assertEquals(55L, result, "Sum of 1..10 should be 55");
    }

    @Test
    void testCompute_largerRange_returnsGaussSum() {
        RecursiveCounter counter = new RecursiveCounter(1, 100);
        long result = new ForkJoinPool().invoke(counter);
        assertEquals(5050L, result, "Sum of 1..100 should be 5050");
    }

    @Test
    void testCompute_singleElement_returnsThatElement() {
        RecursiveCounter counter = new RecursiveCounter(7, 7);
        long result = new ForkJoinPool().invoke(counter);
        assertEquals(7L, result);
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 threads each invoking the RecursiveCounter on the common pool,
     * all ForkJoin workers quickly end up sleeping. ForkJoinTaskBlockingDetector
     * records the blocking call and reports it as a violation.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: replace Thread.sleep() with ForkJoinPool.managedBlock()
     */
    @Disabled("Remove @Disabled to see the bug detected by ForkJoinTaskBlockingDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectForkJoinTaskBlocking = true)
    void testCompute_concurrent_detectsBlockingInTask() throws Exception {
        Thread current = Thread.currentThread();

        // Tell the detector we are entering a ForkJoin task context
        AsyncTestContext.forkJoinTaskBlockingMonitor()
                .recordForkJoinTaskEntered(current);

        // Simulate what RecursiveCounter does: sleep inside the task
        // (The detector intercepts this and records a blocking call)
        AsyncTestContext.forkJoinTaskBlockingMonitor()
                .recordBlockingCallAttempted(current, "Thread.sleep");

        RecursiveCounter counter = new RecursiveCounter(1, 50);
        long result = ForkJoinPool.commonPool().invoke(counter);

        // Signal exit from the ForkJoin task
        AsyncTestContext.forkJoinTaskBlockingMonitor()
                .recordForkJoinTaskExited(current);

        assertEquals(1275L, result, "Sum of 1..50 should be 1275");
    }
}
