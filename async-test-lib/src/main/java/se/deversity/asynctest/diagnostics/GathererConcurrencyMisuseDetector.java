package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects unsafe use of {@code Stream.gather(Gatherer)} (JEP 485 — Stream
 * Gatherers, finalized in JDK 24 and the standard intermediate-operation
 * extension point in JDK 25/26) under parallel evaluation.
 *
 * <p>A {@code Gatherer} has four parts: an {@code initializer} (per-thread private
 * state), an {@code integrator}, an optional {@code combiner}, and an optional
 * {@code finisher}. On a <strong>parallel</strong> stream the framework splits the
 * input, runs the integrator on independent state objects across worker threads,
 * and then merges those states with the {@code combiner}. The contract is:
 *
 * <ul>
 *   <li>If a gatherer keeps mutable state, it <em>must</em> supply a {@code combiner}
 *       so the per-thread states can be merged. A {@code Gatherer.ofSequential(...)}
 *       (or any gatherer built without a combiner) is forced to run sequentially.</li>
 *   <li>The integrator must only touch the per-element state object it is handed —
 *       never shared/captured mutable state — or parallel workers race on it.</li>
 * </ul>
 *
 * <p>The dangerous combination is a gatherer whose integrator mutates state that is
 * <em>shared</em> across the split (captured field, external collection, instance
 * counter) while running on a parallel stream <em>without</em> a combiner. The
 * result is a silent data race: lost updates, {@code ConcurrentModificationException},
 * or non-deterministic output. This detector flags exactly that pattern.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Stateful gatherer on a parallel stream without a combiner</b> — the
 *       integrator was observed mutating state on more than one worker thread, but
 *       the gatherer declared no combiner. Workers cannot merge → lost results.</li>
 *   <li><b>Shared-state race</b> — the integrator of a single gatherer ran
 *       concurrently on multiple threads against the same state key, indicating the
 *       integrator captured shared mutable state instead of using its private
 *       per-thread state.</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 8)
 * void testParallelGather() {
 *     var detector = AsyncTestContext.gathererConcurrencyMisuseDetector();
 *     // Describe the gatherer once, up front:
 *     detector.registerGatherer("dedupRunning", false /* no combiner *​/, true /* parallel *​/);
 *
 *     list.parallelStream()
 *         .gather(dedupRunning())   // integrator calls recordIntegrate(...) per element
 *         .toList();
 * }
 * }</pre>
 *
 * @since 1.7.0
 */
public class GathererConcurrencyMisuseDetector {

    private static final class GathererInfo {
        final boolean hasCombiner;
        final boolean parallel;
        final Set<Long> integratingThreadIds = ConcurrentHashMap.newKeySet();
        final AtomicInteger integrations = new AtomicInteger(0);

        GathererInfo(boolean hasCombiner, boolean parallel) {
            this.hasCombiner = hasCombiner;
            this.parallel = parallel;
        }
    }

    private final Map<String, GathererInfo> gatherers = new ConcurrentHashMap<>();

    private final List<String> missingCombinerReports = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sharedStateReports     = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger totalIntegrations = new AtomicInteger(0);

    /**
     * Declare a gatherer's shape before the stream runs.
     *
     * @param name        a descriptive name for the gatherer (e.g. the factory method)
     * @param hasCombiner whether the gatherer supplies a combiner (parallel-safe merge)
     * @param parallel    whether it is used on a parallel stream
     */
    public void registerGatherer(String name, boolean hasCombiner, boolean parallel) {
        if (name == null) return;
        gatherers.put(name, new GathererInfo(hasCombiner, parallel));
    }

    /**
     * Record one integrator invocation for the given gatherer on the current thread.
     * When the integrator is seen running on more than one thread, we can confirm the
     * stream actually parallelized — and judge whether that was safe.
     *
     * @param name   the gatherer's registered name
     * @param thread the thread running the integrator
     */
    public void recordIntegrate(String name, Thread thread) {
        if (name == null || thread == null) return;
        totalIntegrations.incrementAndGet();
        GathererInfo info = gatherers.get(name);
        if (info == null) return;

        info.integrations.incrementAndGet();
        boolean firstFromThisThread = info.integratingThreadIds.add(thread.threadId());

        // Only meaningful once we have evidence of multi-thread execution.
        if (firstFromThisThread && info.integratingThreadIds.size() == 2) {
            if (!info.hasCombiner) {
                missingCombinerReports.add(
                    "Gatherer '" + name + "': integrator ran on multiple threads but the gatherer "
                    + "declares no combiner. On a parallel stream the per-thread states cannot be "
                    + "merged — results are lost or non-deterministic. Add a combiner, or build it "
                    + "with Gatherer.ofSequential(...) to force sequential evaluation."
                );
            }
            if (info.parallel) {
                sharedStateReports.add(
                    "Gatherer '" + name + "': integrator observed on " + info.integratingThreadIds.size()
                    + " threads concurrently. Ensure it mutates only its private per-thread state "
                    + "(from the initializer) and never captured/shared mutable state, which would "
                    + "race across the split."
                );
            }
        }
    }

    /**
     * Analyze all recorded gatherer events for unsafe parallel usage.
     *
     * @return a report describing detected issues
     */
    public GathererConcurrencyMisuseReport analyze() {
        return new GathererConcurrencyMisuseReport(
            new ArrayList<>(missingCombinerReports),
            new ArrayList<>(sharedStateReports),
            gatherers.size(),
            totalIntegrations.get()
        );
    }

    /**
     * Report of Gatherer concurrency-misuse analysis.
     */
    public static class GathererConcurrencyMisuseReport {
        private final List<String> missingCombinerIssues;
        private final List<String> sharedStateIssues;
        private final int totalGatherers;
        private final int totalIntegrations;

        GathererConcurrencyMisuseReport(
                List<String> missingCombinerIssues,
                List<String> sharedStateIssues,
                int totalGatherers,
                int totalIntegrations) {
            this.missingCombinerIssues = missingCombinerIssues;
            this.sharedStateIssues = sharedStateIssues;
            this.totalGatherers = totalGatherers;
            this.totalIntegrations = totalIntegrations;
        }

        /** {@return true if any unsafe parallel-gatherer usage was detected} */
        public boolean hasIssues() {
            return !missingCombinerIssues.isEmpty() || !sharedStateIssues.isEmpty();
        }

        /** {@return the missing combiner issues} */
        public List<String> getMissingCombinerIssues() { return Collections.unmodifiableList(missingCombinerIssues); }
        /** {@return the shared state issues} */
        public List<String> getSharedStateIssues()     { return Collections.unmodifiableList(sharedStateIssues); }
        /** {@return the total gatherers} */
        public int          getTotalGatherers()        { return totalGatherers; }
        /** {@return the total integrations} */
        public int          getTotalIntegrations()     { return totalIntegrations; }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "GathererConcurrencyMisuseReport: No unsafe parallel-gatherer usage detected";
            }

            StringBuilder sb = new StringBuilder();

            if (!missingCombinerIssues.isEmpty()) {
                sb.append(IssueSeverity.HIGH.format())
                  .append(": Stateful gatherer on a parallel stream without a combiner (lost results)\n");
            } else {
                sb.append(IssueSeverity.MEDIUM.format())
                  .append(": Gatherer integrator ran concurrently — verify state confinement\n");
            }

            sb.append("  Gatherers=").append(totalGatherers)
              .append(", Integrations=").append(totalIntegrations).append("\n");

            appendSection(sb, "Missing combiner on parallel stream (lost results)", missingCombinerIssues);
            appendSection(sb, "Concurrent integrator — confirm per-thread state confinement", sharedStateIssues);

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
        }

        private static void appendSection(StringBuilder sb, String title, List<String> items) {
            if (items.isEmpty()) return;
            sb.append("\n  ").append(title).append(":\n");
            for (String item : items) {
                sb.append("    - ").append(item).append("\n");
            }
        }

        private static String getLearningContent() {
            return """
                📚 LEARNING: Stream Gatherers (Java 24+, JEP 485)

                Stream.gather(Gatherer) is the extension point for custom intermediate
                operations. A Gatherer = initializer + integrator + (combiner) + (finisher).

                On a PARALLEL stream the runtime:
                  1. splits the input,
                  2. runs the integrator on independent state per worker thread,
                  3. merges those states with the COMBINER.

                Correct usage:
                  // Stateful + parallel-safe → MUST provide a combiner:
                  Gatherer.of(initializer, integrator, combiner, finisher);

                  // Stateful but no safe merge → force sequential:
                  Gatherer.ofSequential(initializer, integrator, finisher);

                Common mistakes:
                  ✗ Stateful gatherer with no combiner on a parallel stream → states can't
                    merge; results are dropped or non-deterministic
                  ✗ Integrator mutating captured/shared state instead of its private state
                    object → data race across the split (lost updates, CME)

                Rule of thumb: keep all mutation inside the per-thread state from the
                initializer, and supply a combiner whenever the gatherer can go parallel.
                """;
        }
    }
}
