package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.ForkJoinPoolDetector;
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
 * ParallelSorter.sort() forks the left half of every split and never joins it.
 * The forked task runs, produces a result, and nobody collects it. The caller
 * gets a sorted list that is missing elements, with no error anywhere. If the
 * abandoned half had thrown, nobody would have found out either: an exception in
 * a forked task surfaces at join(), and there is no join().
 *
 * WHAT THIS DETECTOR ACTUALLY MODELS:
 * Fork without join, and exceptions in forked tasks. Not blocking, and not pool
 * starvation. Its javadoc is explicit that the fork/join imbalance is NOT
 * inferred from the recorded counts, because a test that ends mid-computation
 * would show an imbalance without a defect: the code that abandons the task has
 * to say so. Before issue #346 this example demonstrated a Thread.sleep() inside
 * a common-pool task and recorded balanced fork/join pairs plus a task time, none
 * of which this detector reports on, so enabling it produced nothing. Blocking
 * inside a ForkJoin worker is a real bug with a real detector; it is example 51.
 *
 * WHY @Test PASSES:
 * It does not, any more. testSort_forkWithoutJoin_losesData pins the observable
 * symptom without needing a detector at all, which is the strongest kind of
 * example. What a naive test would miss is that a *sorted* list is returned and
 * nothing throws, so an assertion on ordering alone stays green.
 *
 * DETECTOR ENABLED HERE:
 * ForkJoinPoolDetector — a forked task that was never joined. It is the only one
 * this demonstration switches on, so it is the only one that can report.
 *
 * FIX:
 * Match every fork() with a join(), on every path. sortFixed() is that method.
 */
class ParallelSorterTest {

    private ParallelSorter sorter;

    @BeforeEach
    void setUp() {
        sorter = new ParallelSorter();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — the observable half of the bug, no detector required
    // -------------------------------------------------------------------------

    @Test
    void testSortFixed_unsortedList_returnsWholeSortedList() {
        List<Integer> result = sorter.sortFixed(Arrays.asList(5, 3, 1, 4, 2));

        assertEquals(Arrays.asList(1, 2, 3, 4, 5), result);
    }

    @Test
    void testSort_forkWithoutJoin_losesData() {
        List<Integer> input = Arrays.asList(5, 3, 1, 4, 2);

        List<Integer> result = sorter.sort(input);

        assertTrue(result.size() < input.size(),
                "the abandoned halves are gone: " + input.size() + " in, " + result.size() + " out");
        assertEquals(result.stream().sorted().toList(), result,
                "and what comes back is sorted, which is how this survives review");
    }

    @Test
    void testSortFixed_emptyList_returnsEmpty() {
        assertTrue(sorter.sortFixed(List.of()).isEmpty());
    }

    @Test
    void testSortFixed_doesNotMutateOriginal() {
        List<Integer> original = Arrays.asList(3, 1, 2);
        sorter.sortFixed(original);
        assertEquals(Arrays.asList(3, 1, 2), original, "Original list must not be mutated");
    }

    /**
     * The detector's positive direction, driven by the real sorter.
     */
    @Test
    void testForkJoinPoolDetector_abandonedFork_reports() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        wire(detector);

        sorter.sort(Arrays.asList(5, 3, 1, 4, 2));

        assertTrue(detector.analyze().hasIssues(),
                "a fork that was never joined is what this detector reports");
    }

    /**
     * And the other direction, on the same sorter with the join put back. Balanced fork/join
     * pairs are a fork/join sort working, and reporting them would flag every correct one.
     */
    @Test
    void testForkJoinPoolDetector_everyForkJoined_isSilent() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        wire(detector);

        sorter.sortFixed(Arrays.asList(5, 3, 1, 4, 2));

        assertFalse(detector.analyze().hasIssues(),
                "matched fork/join pairs are not a finding");
    }

    private void wire(ForkJoinPoolDetector detector) {
        ForkJoinPool pool = ForkJoinPool.commonPool();
        detector.registerPool(pool, "common-pool", pool.getParallelism());
        sorter.observeForkJoin(
                taskName -> detector.recordFork(pool, "common-pool", taskName),
                taskName -> detector.recordJoin(pool, "common-pool", taskName),
                taskName -> detector.recordForkWithoutJoin("common-pool", taskName));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * The bug: every split forks the left half and abandons it.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      common-pool:sort-left (forked but never joined)
     * 3. Fix: call sortFixed(), which joins both halves
     */
    @Disabled("Remove @Disabled to see the bug detected by ForkJoinPoolDetector")
    @AsyncTest(threads = 8, invocations = 20, detectAll = false,
            detectForkJoinPoolIssues = true, failOn = FailOn.LOW)
    void testSort_concurrent_detectsForkWithoutJoin() {
        ForkJoinPoolDetector detector = AsyncTestContext.forkJoinPoolMonitor();
        ForkJoinPool pool = ForkJoinPool.commonPool();
        detector.registerPool(pool, "common-pool", pool.getParallelism());
        sorter.observeForkJoin(
                taskName -> detector.recordFork(pool, "common-pool", taskName),
                taskName -> detector.recordJoin(pool, "common-pool", taskName),
                taskName -> detector.recordForkWithoutJoin("common-pool", taskName));

        List<Integer> result = sorter.sort(Arrays.asList(9, 4, 7, 1, 3));

        assertFalse(result.isEmpty(), "something comes back, which is the trap");
    }
}
