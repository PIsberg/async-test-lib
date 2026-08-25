package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

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

    /** Builds the single shared counting task. */
    public TaskScheduler() {
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            onExecute.accept(self[0], "counting-task");
            onCapturedMutation.accept(self[0], "count");
            count[0]++;                      // BUG: non-atomic on shared captured state
        };
        this.countingTask = self[0];
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
}
