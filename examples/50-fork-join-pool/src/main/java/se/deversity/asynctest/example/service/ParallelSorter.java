package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Sorts lists of integers using the {@link ForkJoinPool#commonPool()}.
 *
 * <p><strong>Bug:</strong> The task submitted to the common pool calls
 * {@link Thread#sleep} to simulate blocking I/O. Because the common pool has a
 * fixed worker count (processors - 1), all workers can be pinned sleeping,
 * starving every other parallel stream or {@link CompletableFuture} that shares
 * the same pool.
 *
 * <p><strong>Fix:</strong> Use a dedicated {@link ForkJoinPool} with
 * {@link ForkJoinPool.ManagedBlocker} for blocking operations, or offload I/O
 * to a separate {@link java.util.concurrent.ExecutorService}.
 */
public class ParallelSorter {

    /**
     * Sorts a copy of the input list asynchronously on the common {@link ForkJoinPool}.
     *
     * <p>The internal task blocks on a simulated I/O delay — the bug that starves
     * other common-pool tasks.
     *
     * @param data the list to sort
     * @return a future that resolves to the sorted copy
     */
    public CompletableFuture<List<Integer>> sortAsync(List<Integer> data) {
        return CompletableFuture.supplyAsync(() -> {
            // BUG: blocking inside the common ForkJoinPool worker
            try {
                Thread.sleep(5); // simulates blocking I/O; pins the worker thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<Integer> copy = new ArrayList<>(data);
            Collections.sort(copy);
            return copy;
        }, ForkJoinPool.commonPool());
    }

    /** Returns the parallelism of the common pool (useful in tests). */
    public int getCommonPoolParallelism() {
        return ForkJoinPool.commonPool().getParallelism();
    }
}
