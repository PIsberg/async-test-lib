package com.example.agentfixture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * A worker that races a task it hands to an executor: both increment one field with no lock, then
 * the worker waits for the task's future. Unmodified code; under field weaving each read and write
 * is a scheduling point, and under collection weaving the submit and the get are woven.
 */
public final class TaskRaceBean {

    private final ExecutorService pool;
    private int count;

    /**
     * @param pool the executor the task is handed to; shared across rounds, as a test's pool is
     */
    public TaskRaceBean(ExecutorService pool) {
        this.pool = pool;
    }

    /** Submits a task that increments, increments itself, then waits for the task. */
    public void raceWithTask() throws InterruptedException, ExecutionException {
        Future<?> task = pool.submit(this::increment);
        increment();
        task.get();
    }

    private void increment() {
        count = count + 1;
    }

    /** {@return the count, read after the round} */
    public int count() {
        return count;
    }
}
