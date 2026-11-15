package com.example.agentfixture;

import java.util.concurrent.Executor;

/**
 * Hands a task to an executor through a woven {@code execute}, which is what tells the agent which
 * run the task works for, whichever thread the executor runs it on.
 */
public class TaskExecutingBean {

    /**
     * Executes {@code task} on {@code executor}.
     *
     * @param executor the executor, possibly built by another run
     * @param task     the task
     */
    public void execute(Executor executor, Runnable task) {
        executor.execute(task);
    }
}
