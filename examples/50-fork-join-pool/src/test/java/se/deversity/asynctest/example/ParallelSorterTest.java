package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ParallelSorter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ParallelSorter.
 *
 * ========================================================================
 * DETECTOR: ForkJoinPoolDetector
 * ========================================================================
 *
 * THE BUG:
 * ParallelSorter.sortAsync() submits work to ForkJoinPool.commonPool() but the
 * task internally calls Thread.sleep() to simulate blocking I/O. Worker threads
 * in the common pool must not block; pinned workers cannot steal pending tasks,
 * starving every other parallel stream or CompletableFuture that uses the pool.
 *
 * WHY @Test PASSES:
 * A single-threaded test waits for the future and sees the correct sorted result.
 * The 5 ms sleep is invisible because no other tasks are competing for workers.
 *
 * WHY @AsyncTest DETECTS:
 * ForkJoinPoolDetector.recordTaskTime() is called with the observed wall-clock
 * time. The detector flags tasks that hold workers for unexpectedly long durations,
 * indicating blocking inside the pool.
 *
 * FIX:
 * Use a dedicated ForkJoinPool with ManagedBlocker for blocking tasks, or
 * offload I/O to a separate ExecutorService.
 */
class ParallelSorterTest {

    private ParallelSorter sorter;

    @BeforeEach
    void setUp() {
        sorter = new ParallelSorter();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testSortAsync_unsortedList_returnsSortedResult() throws Exception {
        List<Integer> input = Arrays.asList(5, 3, 1, 4, 2);
        List<Integer> result = sorter.sortAsync(input).get();
        assertEquals(Arrays.asList(1, 2, 3, 4, 5), result);
    }

    @Test
    void testSortAsync_emptyList_returnsEmpty() throws Exception {
        List<Integer> result = sorter.sortAsync(List.of()).get();
        assertTrue(result.isEmpty());
    }

    @Test
    void testSortAsync_doesNotMutateOriginal() throws Exception {
        List<Integer> original = Arrays.asList(3, 1, 2);
        sorter.sortAsync(original).get();
        assertEquals(Arrays.asList(3, 1, 2), original,
                "Original list must not be mutated");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 threads flooding the common pool with blocking tasks, all workers
     * are quickly pinned. ForkJoinPoolDetector records each task's duration and
     * flags tasks that block the worker thread for longer than expected.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: use ManagedBlocker or a separate executor for blocking work
     */
    @Disabled("Remove @Disabled to see the bug detected by ForkJoinPoolDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectForkJoinPoolIssues = true)
    void testSortAsync_concurrent_detectsCommonPoolBlocking() throws Exception {
        ForkJoinPool pool = ForkJoinPool.commonPool();

        // Register the pool so the detector can correlate task events
        AsyncTestContext.forkJoinPoolMonitor()
                .registerPool(pool, "common-pool", pool.getParallelism());

        long start = System.currentTimeMillis();

        // Record the fork (task submission)
        AsyncTestContext.forkJoinPoolMonitor()
                .recordFork(pool, "common-pool", "sort-task");

        List<Integer> result = sorter.sortAsync(Arrays.asList(9, 4, 7, 1, 3)).get();

        long elapsed = System.currentTimeMillis() - start;

        // Record join and actual wall-clock time (includes the blocking sleep)
        AsyncTestContext.forkJoinPoolMonitor()
                .recordJoin(pool, "common-pool", "sort-task");
        AsyncTestContext.forkJoinPoolMonitor()
                .recordTaskTime(pool, "common-pool", elapsed);

        assertFalse(result.isEmpty());
    }
}
