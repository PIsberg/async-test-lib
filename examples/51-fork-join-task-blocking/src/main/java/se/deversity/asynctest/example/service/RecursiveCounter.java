package se.deversity.asynctest.example.service;

import java.util.concurrent.RecursiveTask;

/**
 * Counts integers recursively using the ForkJoin framework.
 *
 * <p><strong>Bug:</strong> {@code compute()} calls {@link Thread#sleep} to
 * simulate latency for small tasks. ForkJoin worker threads must not block;
 * a sleeping worker cannot steal pending tasks from the queue, reducing
 * throughput and potentially causing the pool to appear stuck.
 *
 * <p><strong>Fix:</strong> Replace the blocking sleep with
 * {@link java.util.concurrent.ForkJoinPool#managedBlock} so the pool can
 * compensate by creating an additional worker, or restructure to avoid blocking.
 */
public class RecursiveCounter extends RecursiveTask<Long> {

    private static final int THRESHOLD = 10;

    private final long from;
    private final long to;

    public RecursiveCounter(long from, long to) {
        this.from = from;
        this.to = to;
    }

    @Override
    protected Long compute() {
        if (to - from <= THRESHOLD) {
            // BUG: blocking inside a ForkJoin worker thread
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long sum = 0;
            for (long i = from; i <= to; i++) {
                sum += i;
            }
            return sum;
        }

        long mid = (from + to) / 2;
        RecursiveCounter left  = new RecursiveCounter(from, mid);
        RecursiveCounter right = new RecursiveCounter(mid + 1, to);
        left.fork();
        return right.compute() + left.join();
    }
}
