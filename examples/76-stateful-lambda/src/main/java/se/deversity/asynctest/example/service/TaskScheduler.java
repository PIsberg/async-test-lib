package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * BUGGY service that demonstrates shared mutable state captured in a lambda.
 *
 * <p>BUG: one {@code Runnable} is built once, captures a single mutable {@code int[]}, and is
 * then submitted to the pool over and over. Every thread that runs it does {@code count[0]++}
 * on the same array, unsynchronized, so increments are lost. The lambda looks stateless because
 * it has no fields; the state is in what it captured.
 *
 * <p>FIX: capture an {@code AtomicInteger} and call {@code incrementAndGet()}, or build a fresh
 * task with its own counter per submission so no two threads share one.
 *
 * <p>The second task, the peak tracker, has the same bug in a quieter shape: every call reads
 * the captured peak and only a call with a new maximum writes it, so at any moment one thread
 * writes while the others read. FIX: {@code AtomicInteger.accumulateAndGet(sample, Math::max)},
 * or read and write the peak inside {@code synchronized (peak)}.
 *
 * <p>INSTRUMENTATION: StatefulLambdaDetector keys on the identity of the lambda instance, and
 * reports one that ran on more than one thread while mutating what it captured. A fresh lambda
 * per submission is a different identity every time and produces nothing, correctly - it is not
 * shared. So the task has to be the same object, which is also the bug. The two hooks below
 * report it; they default to no-ops, so the production path never touches the test library.
 */
public class TaskScheduler {

    /** BUG: one counter, captured by one task, shared by every thread that runs it. */
    private final int[] count = {0};

    private final Runnable countingTask;

    private volatile BiConsumer<Object, String> onExecute = (task, name) -> { };

    private volatile BiConsumer<Object, String> onCapturedMutation = (task, name) -> { };

    /** BUG: one peak, captured by one tracker, read by every caller and written by few. */
    private final int[] peak = {0};

    private final IntConsumer peakTracker;

    private volatile Consumer<Object> onPeakExecute = task -> { };

    private volatile BiConsumer<Object, Object> onPeakRead = (task, captured) -> { };

    private volatile BiConsumer<Object, Object> onPeakWrite = (task, captured) -> { };

    /** Builds the single shared counting task and the single shared peak tracker. */
    public TaskScheduler() {
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            onExecute.accept(self[0], "counting-task");
            onCapturedMutation.accept(self[0], "count");
            count[0]++;                      // BUG: non-atomic on shared captured state
        };
        this.countingTask = self[0];

        IntConsumer[] tracker = new IntConsumer[1];
        tracker[0] = sample -> {
            onPeakExecute.accept(tracker[0]);
            onPeakRead.accept(tracker[0], peak);
            if (sample > peak[0]) {          // BUG: every caller reads the capture with no lock
                onPeakWrite.accept(tracker[0], peak);
                peak[0] = sample;            // BUG: and the one with a new peak writes it
            }
        };
        this.peakTracker = tracker[0];
    }

    /**
     * {@return the one peak tracker, the same object for every caller}
     *
     * <p>BUG: most calls only read the captured peak, and only a call with a new maximum writes
     * it. A detector that saw only the writes would see one writing thread per new peak and call
     * the tracker unshared; the readers are the other half of the race.
     */
    public IntConsumer peakTracker() {
        return peakTracker;
    }

    /**
     * {@return the highest sample recorded so far, or a lower one if an update was lost}
     */
    public int peak() {
        return peak[0];
    }

    /**
     * {@return the one task instance, the same object for every caller}
     */
    public Runnable countingTask() {
        return countingTask;
    }

    /**
     * {@return the counter's current value, which is at most the number of executions}
     */
    public int count() {
        return count[0];
    }

    /**
     * Schedule n counting tasks on the given pool.
     *
     * <p>BUG: the same task instance is submitted n times, so all n executions share one counter.
     *
     * @param pool executor to submit tasks to
     * @param n    number of submissions
     * @return the futures, for a caller that wants to wait
     */
    public List<Future<?>> scheduleCountingTasks(ExecutorService pool, int n) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(countingTask));
        }
        return futures;
    }

    /**
     * Installs the hooks StatefulLambdaDetector needs. No-ops by default.
     *
     * @param execute          called with the task instance and a label at the top of each run
     * @param capturedMutation called with the task instance and the captured variable's name
     */
    public void observeTask(BiConsumer<Object, String> execute,
                            BiConsumer<Object, String> capturedMutation) {
        this.onExecute = execute;
        this.onCapturedMutation = capturedMutation;
    }

    /**
     * Installs the hooks StatefulLambdaDetector needs for the peak tracker. No-ops by default.
     *
     * @param execute  called with the tracker at the top of each call
     * @param read     called with the tracker and the captured array before every read of it
     * @param write    called with the tracker and the captured array before a new peak is stored
     */
    public void observePeakTracker(Consumer<Object> execute,
                                   BiConsumer<Object, Object> read,
                                   BiConsumer<Object, Object> write) {
        this.onPeakExecute = execute;
        this.onPeakRead = read;
        this.onPeakWrite = write;
    }
}
