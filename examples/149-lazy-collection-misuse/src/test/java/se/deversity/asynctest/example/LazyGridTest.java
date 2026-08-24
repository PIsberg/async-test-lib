package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.LazyCollectionMisuseDetector;
import se.deversity.asynctest.example.service.LazyGrid;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for LazyGrid.
 *
 * ========================================================================
 * DETECTOR: LazyCollectionMisuseDetector
 *           (DetectorType.LAZY_COLLECTION_MISUSE)
 * ========================================================================
 *
 * JEP 526 added List.ofLazy(size, fn) and Map.ofLazy(keys, fn) beside
 * LazyConstant. Where LAZY_CONSTANT_MISUSE covers one holder with one
 * supplier, a lazy collection is n independent at-most-once computations
 * sharing one mapping function, each running on whichever thread asked
 * for that element first.
 *
 * That independence makes possible a failure a single constant cannot
 * have. A mapping function that reaches back into its own collection
 * couples two elements; if the coupling runs both ways, two threads each
 * hold one element and wait for the other. On one thread the JDK sees
 * the cycle and throws IllegalStateException. Across two, nothing does.
 *
 * THE BUG:
 *   - the mapping function for element i reads element j, and j's reads i
 *   - or it is impure: two runs of the same index disagree
 *
 * THE FIX:
 *   - a pure function of the index, with any shared base computed eagerly
 */
class LazyGridTest {

    private LazyCollectionMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LazyCollectionMisuseDetector();
    }

    /** Records one element computation the way a mapping function would. */
    private String compute(String collection, int index, java.util.function.IntFunction<String> fn) {
        detector.recordGet(collection, index, Thread.currentThread());
        detector.recordComputeStart(collection, index, Thread.currentThread());
        String value = fn.apply(index);
        detector.recordComputeEnd(collection, index, Thread.currentThread(), value);
        return value;
    }

    // -----------------------------------------------------------------------
    // Part 1: the bug. Element 0's mapping function reads element 1, and on a
    // later read element 1's reads element 0. Both edges observed, so both
    // directions of the wait are real.
    // -----------------------------------------------------------------------

    @Test
    void mappingFunctionsThatReadEachOther_isDetected() {
        compute("GRID", 0, i -> "cell-" + compute("GRID", 1, j -> "cell-" + j));
        compute("GRID", 1, i -> "cell-" + compute("GRID", 0, j -> "cell-" + j));

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "each element waits on the other:\n" + report);

        var v = report.structuredViolations.stream()
                .filter(x -> "circularElementDependency".equals(x.attributes().get("issue")))
                .findFirst()
                .orElseThrow();
        assertEquals(IssueSeverity.CRITICAL, v.severity());
    }

    // -----------------------------------------------------------------------
    // Part 2: the fix. The shared base is computed eagerly, so the lazy layer
    // is one level deep and no element can wait on another.
    // -----------------------------------------------------------------------

    @Test
    void anEagerBaseLayerUnderALazyGrid_isClean() {
        int[] weights = {10, 20, 30, 40};
        var grid = new LazyGrid(i -> "cell-" + weights[(i + 1) % weights.length]);

        for (int i = 0; i < weights.length; i++) {
            int index = i;
            assertEquals(grid.get(index), compute("GRID", index, j -> grid.get(j)));
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "no element reaches into the collection it belongs to:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: the mapping function must be a pure function of the index. Here
    // it reads a counter, so which value the collection keeps is a race.
    // -----------------------------------------------------------------------

    @Test
    void anImpureMappingFunction_isDetected() {
        AtomicInteger calls = new AtomicInteger();
        compute("SEQ", 3, i -> "v" + calls.incrementAndGet());
        compute("SEQ", 3, i -> "v" + calls.incrementAndGet());

        var report = detector.analyze();
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("produced values that are not equal")));
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("was computed 2 times")));
    }

    // -----------------------------------------------------------------------
    // Part 4: JDK 26 lazy collections do not hold null. The mapping function
    // returning one throws in whichever thread happened to read it first.
    // -----------------------------------------------------------------------

    @Test
    void aNullProducingMappingFunction_isDetected() {
        compute("GRID", 7, i -> null);

        var report = detector.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("computed to null 1 time(s)")));
    }

    // -----------------------------------------------------------------------
    // Part 5: a one-way dependency terminates, so it is not the deadlock -
    // but the outer element is held open for the whole of the inner one, and
    // every reader of the outer waits for both. Reported as the low warning.
    // -----------------------------------------------------------------------

    @Test
    void aOneWayElementDependency_isOnlyTheNestedWarning() {
        compute("GRID", 0, i -> "cell-" + compute("GRID", 1, j -> "cell-" + j));

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("GRID[0] -> GRID[1]")));
        assertFalse(report.violations.stream()
                .anyMatch(v -> v.contains("depend on each other in a cycle")));

        var v = report.structuredViolations.stream()
                .filter(x -> "nestedLazyComputation".equals(x.attributes().get("issue")))
                .findFirst()
                .orElseThrow();
        assertEquals(IssueSeverity.LOW, v.severity());
    }

    // -----------------------------------------------------------------------
    // Part 6: the ordinary case. One computation per element, values that
    // agree, nothing reaching sideways. This is what has to stay silent for
    // the findings above to mean anything.
    // -----------------------------------------------------------------------

    @Test
    void aPureMappingFunctionRunOncePerElement_isClean() {
        var grid = new LazyGrid(i -> "cell-" + i);
        for (int i = 0; i < 8; i++) {
            int index = i;
            assertEquals("cell-" + index, compute("GRID", index, j -> grid.get(j)));
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "a pure mapping function is silent:\n" + report);
    }
}
