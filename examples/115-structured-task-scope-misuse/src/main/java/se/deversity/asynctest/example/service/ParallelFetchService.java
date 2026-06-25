package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Models a structured fan-out over a {@code StructuredTaskScope} (JEP 505).
 *
 * <p>The real JDK 25 API is:
 * <pre>{@code
 * try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
 *     var subtasks = ids.stream().map(id -> scope.fork(() -> fetch(id))).toList();
 *     scope.join();
 *     return subtasks.stream().map(Subtask::get).toList();
 * }
 * }</pre>
 *
 * <p>{@code StructuredTaskScope.open(Joiner)} is a JDK 25 preview API not present on the
 * Java 21 baseline this example targets, so the fan-out here uses a virtual-thread-per-task
 * {@link ExecutorService}. The lifecycle rules being demonstrated — fork → join → get,
 * confined to the owner thread — are identical; the test models them with
 * {@code StructuredTaskScopeMisuseDetector}.
 */
public final class ParallelFetchService {

    /** Correct: fork all, await all, then read results. */
    public List<String> fetchAll(List<String> ids) throws Exception {
        try (ExecutorService scope = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> subtasks = new ArrayList<>();
            for (String id : ids) {
                subtasks.add(scope.submit(() -> fetch(id)));   // fork
            }
            // try-with-resources close() awaits termination — our "join"
            scope.shutdown();
            List<String> results = new ArrayList<>();
            for (Future<String> s : subtasks) {
                results.add(s.get());                          // read after join
            }
            return results;
        }
    }

    private String fetch(String id) {
        return "result-for-" + id;
    }
}
