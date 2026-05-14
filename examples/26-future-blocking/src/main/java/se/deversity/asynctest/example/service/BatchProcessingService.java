package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A batch processing service that fans out work items across a fixed thread pool.
 *
 * BUG: processBatch() is itself executed by a worker thread drawn from
 * {@code workerPool}. It submits N subtasks to the same {@code workerPool}
 * and then calls Future.get() for each one.
 *
 * When the pool is fully occupied — e.g. multiple callers each invoke
 * processBatch() concurrently — every worker thread ends up blocking on a
 * future whose subtask is queued behind other blocked workers. No thread
 * is free to drain the queue. The pool stalls and throughput drops to zero.
 *
 * FIX: Use a dedicated I/O thread pool for the individual item subtasks so
 * the batch-orchestrating threads are never sharing the same pool as the work
 * they are waiting for. Alternatively use CompletableFuture.allOf() with
 * non-blocking composition.
 */
public class BatchProcessingService {

    private final ExecutorService workerPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "batch-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Process a list of items by submitting each as an independent subtask
     * to the worker pool, then gathering all results.
     *
     * STARVATION RISK: if this method itself runs inside {@code workerPool},
     * each blocking Future.get() consumes a pool thread. With 4 concurrent
     * callers and 4 pool threads, all threads are blocked and the subtasks
     * can never start.
     */
    public List<String> processBatch(List<String> items)
            throws ExecutionException, InterruptedException {

        List<Future<String>> futures = new ArrayList<>();
        for (String item : items) {
            futures.add(workerPool.submit(() -> processItem(item)));
        }

        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get()); // blocks the calling thread
        }
        return results;
    }

    private String processItem(String item) {
        // Simulate transformation work (e.g. enrichment lookup, validation)
        return item.trim().toUpperCase() + "_PROCESSED";
    }

    public ExecutorService getWorkerPool() {
        return workerPool;
    }

    public void shutdown() {
        workerPool.shutdownNow();
    }
}
