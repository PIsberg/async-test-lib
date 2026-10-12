package com.example.agentfixture;

import org.slf4j.MDC;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs request-scoped work on a pool the way a service does, putting an MDC key for the request and
 * recording nothing. Woven with {@code collections=true}, the {@code submit} hands the pool a task
 * wrapper that marks where each task starts and ends.
 */
public class MdcTaskBean implements AutoCloseable {

    private final ExecutorService pool = Executors.newFixedThreadPool(1);

    /**
     * Puts a request id in a pooled task and never clears it: the next task on that thread logs
     * with this request's id.
     *
     * @throws Exception if the task fails
     */
    public void leaveTheKeyBehind() throws Exception {
        run(() -> MDC.put("requestId", Thread.currentThread().getName()));
    }

    /**
     * Puts a request id in a pooled task and clears the MDC in {@code finally}.
     *
     * @throws Exception if the task fails
     */
    public void clearInFinally() throws Exception {
        run(() -> {
            MDC.put("requestId", Thread.currentThread().getName());
            try {
                Thread.onSpinWait();
            } finally {
                MDC.clear();
            }
        });
    }

    private void run(Runnable task) throws InterruptedException, ExecutionException {
        pool.submit(task).get();
    }

    @Override
    public void close() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
