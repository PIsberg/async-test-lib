package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.asynctest.example.service.GraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for GraphService.
 *
 * ========================================================================
 * DETECTOR: ConcurrentMapComputeRecursionDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * GraphService.getNeighbors() uses ConcurrentHashMap.computeIfAbsent().
 * The lambda for node "A" calls getNeighbors("B"), which triggers another
 * computeIfAbsent() on the same map, for a different key. ConcurrentHashMap's
 * javadoc forbids that: the mapping function must not modify the map during
 * computation. Measured on JDK 26 over 200 fresh maps, the nested call ran and
 * returned 198 times and threw IllegalStateException("Recursive update") twice,
 * on the runs where both keys landed in the same bin. The usual outcome is
 * therefore silent: an adjacency list built in an order the caller did not intend.
 *
 * NOTE: ConcurrentMapComputeRecursionDetector keys on map, key and thread together,
 * so it does not report a different-key re-entry and stays silent on this example.
 * See issue #343.
 *
 * WHY @Test PASSES:
 * The first sequential call to getNeighbors("A") may trigger the recursion,
 * but in a single thread the JVM unwinds the stack before deadlocking.
 * Java 9+ ConcurrentHashMap returns null from the recursive computeIfAbsent,
 * so the outer lambda completes — the result is wrong but no exception is
 * thrown, so a naive assertion passes.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * Multiple threads race to compute the same key simultaneously.
 * ConcurrentMapComputeRecursionDetector tracks every computeIfAbsent entry
 * and exit and flags cases where the same thread re-enters compute for the
 * same map before the first compute finishes.
 *
 * DETECTORS TRIGGERED:
 *   ConcurrentMapComputeRecursionDetector — primary: recursive compute detected
 *
 * FIX: precompute the adjacency list eagerly in the constructor.
 */
class GraphServiceTest {

    private GraphService service;

    @BeforeEach
    void setUp() {
        service = new GraphService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, appears to pass
    // -----------------------------------------------------------------------

    @Test
    void testGetNeighbors_nodeB_singleThread() {
        // "B" does not trigger the recursive path — returns directly
        List<String> neighbors = service.getNeighbors("B");
        assertNotNull(neighbors);
        assertFalse(neighbors.isEmpty());
    }

    @Test
    void testGetNeighbors_cachedResult_sameReference() {
        service.getNeighbors("B");
        List<String> first = service.getAdjacency().get("B");
        List<String> second = service.getAdjacency().get("B");
        assertSame(first, second, "Cached result should be the same object");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes recursive computeIfAbsent
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see the recursive compute fail the run, as the README describes")
    // failOn/minTrust are set so this test does what the README says it does. failOn defaults
    // to NONE, which reports a finding without failing, and this detector is PROMPT tier, so
    // without both of these the run prints the report and still goes green.
    @AsyncTest(threads = 8, invocations = 50, detectAll = false,
            detectConcurrentMapComputeRecursion = true,
            failOn = FailOn.HIGH, minTrust = TrustTier.PROMPT)
    void testGetNeighbors_concurrent_detectsRecursion() {
        var map = service.getAdjacency();

        // The recording has to happen INSIDE the mapping function, because that is what the
        // detector's contract asks for and it is the only place the nesting is visible. Recording
        // around service.getNeighbors("A") instead would see one balanced start/end per body
        // execution and nothing nested, which is what this test used to do and why it reported
        // nothing however long it ran.
        service.observeComputes(
                key -> AsyncTestContext.get().concurrentMapComputeRecursionMonitor()
                        .recordComputeStart(map, key, Thread.currentThread(), "adjacency-map"),
                key -> AsyncTestContext.get().concurrentMapComputeRecursionMonitor()
                        .recordComputeEnd(map, key, Thread.currentThread()));

        List<String> neighbors = service.getNeighbors("A");
        assertNotNull(neighbors, "neighbors must not be null");
    }
}
