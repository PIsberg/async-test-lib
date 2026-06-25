package se.deversity.asynctest.example.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Models a stateful {@code Gatherer} used on a parallel stream (JEP 485, Stream Gatherers).
 *
 * <p>The real JDK 24+ shape is a custom intermediate operation:
 * <pre>{@code
 * Gatherer<T,?,T> runningDistinct = Gatherer.ofSequential(
 *     HashSet::new,                                  // per-thread state
 *     (state, elem, downstream) ->                   // integrator
 *         state.add(elem) ? downstream.push(elem) : true);
 * list.parallelStream().gather(runningDistinct).toList();   // ✗ no combiner → can't merge
 * }</pre>
 *
 * <p>{@code Stream.gather} / {@code java.util.stream.Gatherer} are not present on the
 * Java 21 baseline this example targets, so the stateful "running distinct" reduction is
 * shown here with a hand-rolled integrator over a shared {@link Set}. The hazard is the
 * same: a stateful integrator with shared state and no safe merge cannot be parallelized
 * correctly.
 */
public final class RunningDistinctService {

    /**
     * BUGGY: a single shared {@link HashSet} is mutated from every worker thread while the
     * stream runs in parallel — the gatherer equivalent of "stateful integrator, shared
     * state, no combiner". Produces lost / duplicated output and may throw under contention.
     */
    public List<String> distinctParallelBuggy(List<String> input) {
        Set<String> seen = new HashSet<>();                 // shared, non-thread-safe state
        return input.parallelStream()
                .filter(seen::add)                          // races across the split
                .toList();
    }

    /**
     * CORRECT: distinct is computed without shared mutable state. A real gatherer would
     * supply a combiner (or use {@code Stream.distinct()} / a concurrent collector) so
     * per-thread states merge safely.
     */
    public List<String> distinctParallelSafe(List<String> input) {
        return input.parallelStream()
                .distinct()
                .toList();
    }
}
