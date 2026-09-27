package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.StatefulLambdaDetector;
import se.deversity.asynctest.example.service.TaskScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TaskScheduler.
 *
 * ========================================================================
 * DETECTOR: StatefulLambdaDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (single thread, no contention on the array)
 * - The same scenario with @AsyncTest FAILS (lost updates on shared int[])
 *
 * THE BUG:
 * TaskScheduler builds one Runnable, once, capturing a single int[]. That one
 * object is submitted to the pool over and over, so every thread that runs it
 * does count[0]++ on the same array. The increment is a non-atomic
 * read-modify-write; under concurrency two threads read the same value and both
 * write back N+1, and one increment is gone. The lambda looks stateless because
 * it has no fields; the state is in what it captured.
 *
 * WHY @Test PASSES:
 * With a single thread, count[0]++ executes sequentially with no interleaving.
 * The final count equals n — no updates are lost.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * StatefulLambdaDetector keys on the *identity of the lambda instance*.
 * recordExecution tracks which threads ran that instance, recordCapturedMutation
 * records writes to its captures, and a finding is one instance on more than one
 * thread with mutations recorded.
 *
 * That identity is what this example used to get wrong: the demonstration built a
 * fresh lambda inside the body, so the detector saw 400 lambdas with one thread
 * each and said nothing - correctly, because none of them was shared. The task is
 * now the same object on every thread, which is both what the detector needs and
 * what the bug is. See issue #346.
 *
 * One thing to read carefully in the report: the thread count is the number of
 * distinct thread ids, and @AsyncTest runs on virtual threads by default, one per
 * body execution. With threads = 8, invocations = 50 it reads 400 rather than 8.
 * See issue #349.
 *
 * DETECTOR ENABLED HERE:
 *   StatefulLambdaDetector — one lambda instance mutating its capture from several
 *   threads. It is the only one this demonstration switches on, so it is the only
 *   one that can report.
 *
 * FIX: replace int[] with AtomicInteger; or give each submitted task its own
 *      independent counter so threads never share mutable state.
 */
class TaskSchedulerTest {

    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TaskScheduler();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, always correct
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_schedulerIsCreated() {
        assertNotNull(scheduler);
    }

    @Test
    void test_singleThread_submitsTasks() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var futures = scheduler.scheduleCountingTasks(pool, 3);
        assertEquals(3, futures.size());
        for (var f : futures) f.get();
        pool.shutdown();
    }

    /**
     * The detector's positive direction: one task object, two threads, mutations recorded.
     */
    @Test
    void testStatefulLambdaDetector_oneTaskOnTwoThreads_reports() throws Exception {
        StatefulLambdaDetector detector = new StatefulLambdaDetector();
        wire(detector);

        Thread first = new Thread(scheduler.countingTask(), "worker-1");
        Thread second = new Thread(scheduler.countingTask(), "worker-2");
        first.start();
        second.start();
        first.join(2000);
        second.join(2000);

        assertTrue(detector.analyze().hasIssues(),
                "one lambda instance mutating its capture on two threads is the race");
    }

    /**
     * And the other direction: the same task run any number of times on one thread is not
     * shared, and a detector that reported it would flag every loop in the program.
     */
    @Test
    void testStatefulLambdaDetector_sameThreadOnly_isSilent() {
        StatefulLambdaDetector detector = new StatefulLambdaDetector();
        wire(detector);

        scheduler.countingTask().run();
        scheduler.countingTask().run();
        scheduler.countingTask().run();

        assertEquals(3, scheduler.count(), "no contention, so nothing is lost");
        assertFalse(detector.analyze().hasIssues(),
                "one thread cannot race itself, however often it runs the task");
    }

    private void wire(StatefulLambdaDetector detector) {
        scheduler.observeTask(
                (task, name) -> detector.recordExecution(task, name, Thread.currentThread()),
                (task, name) -> detector.recordCapturedMutation(task, name, Thread.currentThread()));
    }

    /**
     * One writer beside a reader: the first thread brings a new peak and writes it, the second
     * brings a sample below the peak and only reads. Recording only the write would put one
     * thread on the capture and report nothing; recordCapturedRead puts the reader there too,
     * and neither access held a lock.
     */
    @Test
    void testStatefulLambdaDetector_peakReadBesideItsWriter_reports() throws Exception {
        StatefulLambdaDetector detector = new StatefulLambdaDetector();
        wirePeakTracker(detector);

        // Started together and joined after, so nothing orders the read after the write: a join
        // between them would, and the read would then be no race.
        Thread writer = new Thread(() -> scheduler.peakTracker().accept(10), "writer");
        Thread reader = new Thread(() -> scheduler.peakTracker().accept(0), "reader");
        writer.start();
        reader.start();
        writer.join(2000);
        reader.join(2000);

        assertEquals(10, scheduler.peak(), "the reader's sample was below the peak, so only the writer wrote");
        assertTrue(detector.analyze().hasIssues(),
                "one thread wrote the captured peak and another read it, and no lock covered both");
    }

    /**
     * And the other direction: callers that only read the peak, because nobody beats it, share
     * nothing mutable. Concurrent reads of state nobody writes are no race.
     */
    @Test
    void testStatefulLambdaDetector_readersWithNoWriter_isSilent() throws Exception {
        StatefulLambdaDetector detector = new StatefulLambdaDetector();
        wirePeakTracker(detector);

        Thread first = new Thread(() -> scheduler.peakTracker().accept(0), "reader-1");
        Thread second = new Thread(() -> scheduler.peakTracker().accept(0), "reader-2");
        first.start();
        second.start();
        first.join(2000);
        second.join(2000);

        assertEquals(0, scheduler.peak(), "no sample beat the initial peak");
        assertFalse(detector.analyze().hasIssues(), "two threads read, nobody wrote");
    }

    private void wirePeakTracker(StatefulLambdaDetector detector) {
        scheduler.observePeakTracker(
                task -> detector.recordExecution(task, "peak-tracker", Thread.currentThread()),
                (task, peak) -> detector.recordCapturedRead(task, peak, Thread.currentThread()),
                (task, peak) -> detector.recordCapturedMutation(task, "peak", peak, Thread.currentThread()));
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes shared mutable lambda capture
    // -----------------------------------------------------------------------

    /**
     * The bug: eight threads run the same task object, which increments the one counter it
     * captured.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      'counting-task' executed on N threads (...) with concurrent captured-state mutations
     * 3. Fix: capture an AtomicInteger, or build a fresh task with its own counter per submission
     */
    @Disabled("Remove @Disabled to see shared mutable lambda capture detected by StatefulLambdaDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false,
            detectStatefulLambda = true, failOn = FailOn.LOW)
    void test_concurrent_detectsStatefulLambda() {
        // The task has to be the same object on every thread, which is the bug. This
        // demonstration used to build a fresh lambda inside the body, so the detector - which
        // keys on the lambda's identity - saw 400 lambdas with one thread each and reported
        // nothing, correctly. See issue #346.
        StatefulLambdaDetector detector = AsyncTestContext.get().statefulLambdaDetector();
        scheduler.observeTask(
                (task, name) -> detector.recordExecution(task, name, Thread.currentThread()),
                (task, name) -> detector.recordCapturedMutation(task, name, Thread.currentThread()));

        scheduler.countingTask().run();
    }

    /**
     * The quieter shape: eight threads feed samples to one peak tracker. Most calls only read
     * the captured peak, and only a call with a new maximum writes it, so a round can hold a
     * single writer. The reads are recorded with recordCapturedRead, which is what makes that
     * round two threads on one capture instead of one.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test; it fails with
     *      'peak-tracker' executed on N threads (...) with concurrent captured-state mutations
     * 3. Fix: AtomicInteger.accumulateAndGet(sample, Math::max), or synchronized (peak) around
     *    both the read and the write
     */
    @Disabled("Remove @Disabled to see a captured read racing its writer detected by StatefulLambdaDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false,
            detectStatefulLambda = true, failOn = FailOn.LOW)
    void test_concurrent_detectsPeakReadRacingItsWriter() {
        StatefulLambdaDetector detector = AsyncTestContext.get().statefulLambdaDetector();
        wirePeakTracker(detector);

        scheduler.peakTracker().accept(ThreadLocalRandom.current().nextInt(1_000));
    }
}
