package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.GathererConcurrencyMisuseDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.stream.Gatherer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same hazard as {@link RunningDistinctServiceTest}, through a real {@link Gatherer}
 * (JEP 485, JDK 24+). Compiled and run only on JDK 24 or later, by the {@code jdk24-gatherer}
 * profile in this example's pom; CI runs it in the {@code examples-jdk25} job (#893).
 *
 * <p>Both gatherers declare a combiner, so the JDK runs their integrator in parallel, one
 * segment per initializer call. The integrator reports the state object it was handed, which is
 * the evidence the detector judges a gatherer with a combiner on.
 */
class RealGathererTest {

    private static final String NAME = "running-distinct";

    /** Enough elements that a parallel stream splits them across several workers. */
    private static final int ELEMENTS = 20_000;

    /** Stream evaluations to try before giving up on seeing two worker threads. */
    private static final int ATTEMPTS = 50;

    private GathererConcurrencyMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new GathererConcurrencyMisuseDetector();
        detector.registerGatherer(NAME, /*hasCombiner*/ true, /*parallel*/ true);
    }

    /** A running-distinct gatherer whose segments get their state from {@code initializer}. */
    private Gatherer<Integer, Set<Integer>, Integer> runningDistinct(Supplier<Set<Integer>> initializer) {
        return Gatherer.of(
            initializer,
            (state, element, downstream) -> {
                detector.recordIntegrate(NAME, state, Thread.currentThread());
                return !state.add(element) || downstream.push(element);
            },
            (left, right) -> {
                left.addAll(right);
                return left;
            },
            (state, downstream) -> { });
    }

    /**
     * Evaluates {@code gatherer} on a parallel stream in a four-worker pool until its
     * integrator has run on two threads, so the split this test is about really happened.
     */
    private void gatherInParallel(Gatherer<Integer, Set<Integer>, Integer> gatherer) throws Exception {
        Set<Long> threads = ConcurrentHashMap.newKeySet();
        List<Integer> input = IntStream.range(0, ELEMENTS).map(i -> i % 1_000).boxed().toList();
        ForkJoinPool pool = new ForkJoinPool(4);
        try {
            for (int attempt = 0; attempt < ATTEMPTS && threads.size() < 2; attempt++) {
                pool.submit(() -> input.parallelStream()
                        .peek(e -> threads.add(Thread.currentThread().threadId()))
                        .gather(gatherer)
                        .toList()).get();
            }
        } finally {
            pool.shutdown();
        }
        assertTrue(threads.size() >= 2,
            "precondition: the parallel stream ran on " + threads.size() + " thread(s)");
    }

    @Test
    void anInitializerReturningOneSharedSetIsReported() throws Exception {
        // One set, captured. A HashSet here is the real bug, and could also throw from inside the
        // stream; a concurrent set keeps this test deterministic and still breaks the contract
        // of one state per initializer call, which is what the detector reports.
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        gatherInParallel(runningDistinct(() -> seen));       // every segment gets the same set

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "segments integrated one shared state: " + report);
        assertFalse(report.getSharedStateIssues().isEmpty(), report.toString());
    }

    @Test
    void anInitializerReturningAFreshSetPerSegmentIsSilent() throws Exception {
        gatherInParallel(runningDistinct(HashSet::new));     // one set per initializer call

        var report = detector.analyze();
        assertTrue(report.getSharedStateIssues().isEmpty(),
            "each segment kept to its own state: " + report);
        assertFalse(report.hasIssues(), report.toString());
    }
}
