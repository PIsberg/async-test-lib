package com.example.agentfixture;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Plain fields a pool's tasks write and the submitter updates once the pool has terminated, or
 * before it has (#1266). Woven by the e2e test that drives it, so every submit, execute,
 * awaitTermination and close below goes through the agent's hook.
 *
 * <p>Each task writes a box of its own, so the tasks never race each other; the only pair that
 * can race is a task and the submitter.
 */
public class TerminationBean {

    /** The plain field each task writes and the submitter updates. */
    public static final class Box {
        int value;
    }

    private final Box[] boxes = {new Box(), new Box(), new Box()};

    /** Updates every box, a read and a write each, and returns the sum. */
    private int updateAll() {
        int sum = 0;
        for (Box box : boxes) {
            sum += ++box.value;
        }
        return sum;
    }

    /** Submits one writing task per box, shuts the pool down and awaits it, then updates. */
    public int submitThenAwaitTermination(ExecutorService pool) throws InterruptedException {
        for (Box box : boxes) {
            pool.submit(() -> box.value = 7);
        }
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the pool did not terminate");
        }
        return updateAll();
    }

    /** The same through {@code execute}, which has no future to order anything. */
    public int executeThenAwaitTermination(ExecutorService pool) throws InterruptedException {
        for (Box box : boxes) {
            ((Executor) pool).execute(() -> box.value = 7);
        }
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the pool did not terminate");
        }
        return updateAll();
    }

    /** Submits to a virtual-thread executor in a try-with-resources block, then updates. */
    public int submitInsideTryWithResources() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Box box : boxes) {
                pool.submit(() -> box.value = 7);
            }
        }
        return updateAll();
    }

    /**
     * Submits, waits for the tasks through {@code written}, which the agent does not see, and
     * updates before the pool is awaited.
     */
    public int updateBeforeAwaitTermination(ExecutorService pool, Runnable taskDone, Runnable written)
            throws InterruptedException {
        for (Box box : boxes) {
            pool.submit(() -> {
                box.value = 7;
                taskDone.run();
            });
        }
        written.run();
        int sum = updateAll();
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the pool did not terminate");
        }
        return sum;
    }

    /**
     * One task writes the first box and ends; another holds the pool open. The submitter waits
     * through {@code firstEnded}, which the agent does not see, until the first task has ended,
     * shuts down, awaits briefly, sees {@code false}, and updates the first box anyway.
     *
     * @return the update's result, or {@code -1} when the wait unexpectedly reported termination
     */
    public int updateAfterAwaitTerminationTimedOut(ExecutorService pool, Runnable firstEnded,
                                                   Runnable holdOpen) throws InterruptedException {
        Box first = boxes[0];
        pool.submit(() -> first.value = 7);
        pool.submit(holdOpen);
        firstEnded.run();
        pool.shutdown();
        if (pool.awaitTermination(50, TimeUnit.MILLISECONDS)) {
            return -1;
        }
        return ++first.value;
    }

    /**
     * Executes a writing task on the common pool, waits for the pool to go quiet through
     * {@code quiesced}, which the agent does not see, calls {@code close} on the common pool,
     * which the JDK documents as having no effect, and updates.
     */
    public int updateAfterClosingTheCommonPool(Runnable quiesced) {
        Box first = boxes[0];
        ForkJoinPool common = ForkJoinPool.commonPool();
        ((Executor) common).execute(() -> first.value = 7);
        quiesced.run();
        ((ExecutorService) common).close();
        return ++first.value;
    }
}
