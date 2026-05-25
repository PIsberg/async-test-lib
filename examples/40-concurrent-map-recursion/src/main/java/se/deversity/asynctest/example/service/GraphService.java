package se.deversity.asynctest.example.service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BUGGY service that demonstrates recursive ConcurrentHashMap.computeIfAbsent.
 *
 * BUG: getNeighbors() uses computeIfAbsent() on a ConcurrentHashMap.
 *      The lambda calls getNeighbors() for a related node, which triggers
 *      another computeIfAbsent() on the same map. This recursive compute
 *      hangs in Java 8 and throws IllegalStateException in Java 9+.
 *
 * FIX: Precompute the full adjacency list eagerly (e.g., in the constructor)
 *      outside any computeIfAbsent lambda, or use a separate helper method
 *      that does not re-enter the same map bucket.
 */
public class GraphService {

    private final ConcurrentHashMap<String, List<String>> adjacency = new ConcurrentHashMap<>();

    /**
     * Returns the neighbours of the given node, computing them lazily.
     * BUG: the lambda calls getNeighbors() again — recursive computeIfAbsent.
     */
    public List<String> getNeighbors(String node) {
        return adjacency.computeIfAbsent(node, key -> {
            // Simulate: "A" always references "B", which back-references "A"
            if ("A".equals(key)) {
                // BUG: recursive call — re-enters computeIfAbsent on same map
                List<String> bNeighbors = getNeighbors("B");
                return Arrays.asList("B", "C");
            }
            return Arrays.asList("A", "D");
        });
    }

    public ConcurrentHashMap<String, List<String>> getAdjacency() {
        return adjacency;
    }
}
