package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * BUGGY service that demonstrates structured-concurrency scope leak.
 *
 * <p>BUG: {@link #fetchAll} creates a virtual-thread executor (analogous to a
 * {@code StructuredTaskScope}) to run subtasks in parallel but never calls
 * {@code shutdown()} / {@code close()} on it. Each call leaks the executor and
 * its threads — the same resource-leak hazard that
 * {@code StructuredConcurrencyMisuseDetector} flags when a
 * {@code StructuredTaskScope} is not closed.
 *
 * <p>FIX: wrap the executor in try-with-resources:
 * <pre>{@code
 * try (ExecutorService scope = Executors.newVirtualThreadPerTaskExecutor()) {
 *     List<Future<String>> futures = ids.stream()
 *         .map(id -> scope.submit(() -> fetch(id)))
 *         .toList();
 *     return futures.stream().map(DataFetchService::get).toList();
 * }  // scope.close() shuts down and awaits termination
 * }</pre>
 */
public class DataFetchService {

    /**
     * Fetch data for each ID in parallel.
     * BUG: the executor (scope) is never shut down — virtual threads and
     * internal resources leak on every call.
     *
     * @param ids list of IDs to fetch
     * @return list of fetched results
     */
    public List<String> fetchAll(List<String> ids) throws Exception {
        // BUG: not in try-with-resources; shutdown()/close() is never called.
        ExecutorService scope = Executors.newVirtualThreadPerTaskExecutor();

        List<Future<String>> futures = new ArrayList<>();
        for (String id : ids) {
            futures.add(scope.submit(() -> "result-for-" + id));
        }

        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get());
        }

        // BUG: scope.shutdown() / scope.close() never called — leak.
        return results;
    }
}
