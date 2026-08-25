package se.deversity.asynctest.example.service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * BUGGY service that demonstrates recursive ConcurrentHashMap.computeIfAbsent.
 *
 * BUG: getNeighbors() uses computeIfAbsent() on a ConcurrentHashMap.
 *      The lambda calls getNeighbors() for a related node, which triggers
 *      another computeIfAbsent() on the same map. This recursive compute
 *      breaks ConcurrentHashMap's stated contract: "the mapping function must
 *      not modify this map". It usually returns normally, which is why this
 *      shape survives review; it throws IllegalStateException on the runs where
 *      the two keys land in the same bin, and either way the adjacency list is
 *      built in an order the caller did not intend.
 *
 * FIX: Precompute the full adjacency list eagerly (e.g., in the constructor)
 *      outside any computeIfAbsent lambda, or use a separate helper method
 *      that does not re-enter the same map.
 *
 * INSTRUMENTATION: ConcurrentMapComputeRecursionDetector is recording-fed, so it
 *      cannot see a mapping function that does not say when it was entered. The
 *      two hooks below are how a caller tells it. They are plain
 *      java.util.function.Consumer, default to no-ops, and the production path
 *      never touches the test library; GraphServiceTest wires them to the
 *      detector. This is the seam, not the bug.
 */
public class GraphService {

    private final ConcurrentHashMap<String, List<String>> adjacency = new ConcurrentHashMap<>();

    private volatile Consumer<String> onComputeEnter = key -> { };

    private volatile Consumer<String> onComputeExit = key -> { };

    /**
     * Returns the neighbours of the given node, computing them lazily.
     * BUG: the lambda calls getNeighbors() again — recursive computeIfAbsent.
     */
    public List<String> getNeighbors(String node) {
        return adjacency.computeIfAbsent(node, key -> {
            onComputeEnter.accept(key);
            try {
                // Simulate: "A" always references "B", which back-references "A"
                if ("A".equals(key)) {
                    // BUG: re-enters computeIfAbsent on the same map, for another key
                    List<String> bNeighbors = getNeighbors("B");
                    return Arrays.asList("B", "C");
                }
                return Arrays.asList("A", "D");
            } finally {
                onComputeExit.accept(key);
            }
        });
    }

    /**
     * Installs the hooks the detector needs. No-ops by default, so production
     * behaviour is unchanged whether or not a test is watching.
     *
     * @param enter called with the key at the top of each mapping function
     * @param exit  called with the key as each mapping function returns
     */
    public void observeComputes(Consumer<String> enter, Consumer<String> exit) {
        this.onComputeEnter = enter;
        this.onComputeExit = exit;
    }

    public ConcurrentHashMap<String, List<String>> getAdjacency() {
        return adjacency;
    }
}
