package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates processed orders using a parallel stream that writes into a
 * shared, non-thread-safe {@link ArrayList}.
 *
 * BUG: {@code parallelStream().forEach()} dispatches work to multiple threads
 * from the ForkJoinPool common pool. Each thread calls {@code results.add()},
 * but {@code ArrayList} is not thread-safe. Concurrent adds corrupt the internal
 * array state, causing lost updates or {@link java.util.ConcurrentModificationException}.
 */
public class OrderAggregator {

    // BUG: shared mutable field written from multiple parallel-stream threads
    private final List<String> results = new ArrayList<>();

    /**
     * Processes all orders in parallel and accumulates results.
     *
     * BUG: forEach with a side effect on a non-thread-safe collection.
     */
    public void processOrders(List<String> orders) {
        orders.parallelStream()
              .forEach(order -> results.add(process(order))); // data race on results
    }

    /**
     * Simulates order processing — CPU-bound transformation.
     */
    private String process(String order) {
        return "processed:" + order.toUpperCase();
    }

    /**
     * Returns the accumulated results.
     * May be incomplete or corrupted due to the parallel-stream bug.
     */
    public List<String> getResults() {
        return results;
    }

    /**
     * Clears accumulated results between runs.
     */
    public void clear() {
        results.clear();
    }
}
