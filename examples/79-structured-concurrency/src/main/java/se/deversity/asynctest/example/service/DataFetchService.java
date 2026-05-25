package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

/**
 * BUGGY service that demonstrates StructuredTaskScope leak.
 *
 * BUG: fetchAll() creates a StructuredTaskScope.ShutdownOnFailure, forks
 *      subtasks, and calls join() — but never calls close(). StructuredTaskScope
 *      implements AutoCloseable because close() is required to release internal
 *      resources and wait for any remaining virtual threads to terminate.
 *      Without it, resources leak on every call.
 *
 * FIX: open the scope inside try-with-resources:
 *
 * <pre>{@code
 * try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
 *     ids.forEach(id -> scope.fork(() -> fetch(id)));
 *     scope.join().throwIfFailed();
 * }
 * }</pre>
 */
public class DataFetchService {

    /**
     * Fetch data for each ID in parallel using structured concurrency.
     * BUG: the scope is never closed.
     *
     * @param ids list of IDs to fetch
     * @return list of fetched results
     */
    @SuppressWarnings("preview")
    public List<String> fetchAll(List<String> ids) throws Exception {
        // BUG: scope is not in try-with-resources; close() is never called.
        var scope = new StructuredTaskScope.ShutdownOnFailure();

        List<StructuredTaskScope.Subtask<String>> subtasks = new ArrayList<>();
        for (String id : ids) {
            subtasks.add(scope.fork(() -> "result-for-" + id));
        }

        scope.join().throwIfFailed();

        // BUG: scope.close() is missing — threads and internal state leak.
        return subtasks.stream().map(StructuredTaskScope.Subtask::get).toList();
    }
}
