package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

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
 *   <li><b>Shared-state race</b> — one state object of a parallel gatherer that has a
 *       combiner was integrated on two threads, so the segments are not confined to their
 *       own state. This needs the state object, from
 *       {@link #recordIntegrate(String, Object, Thread)}; a gatherer with a combiner whose
 *       integrator merely runs on several threads is the correct parallel shape and is
 *       silent.</li>
 * </ul>
 *
 * <p>Labels identify gatherers. Registering one label twice with different shapes marks it
 * ambiguous, and no missing-combiner verdict is drawn from its integrations.
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

    /**
     * Past this many tracked state objects the ownership map is cleared. A state object only has
     * to be remembered while its segment is still being integrated, and every finished stream
     * leaves its states behind; without a bound a long run would keep every dedup set and buffer
     * any gatherer ever built reachable until the test ends. Clearing can lose one sharing
     * observation that straddles the clear, which costs a missed finding and never a false one.
     */
    private static final int MAX_TRACKED_STATES = 4_096;

    private static final class GathererInfo {
        final boolean hasCombiner;
        final boolean parallel;
        final Set<Long> integratingThreadIds = ConcurrentHashMap.newKeySet();
        final AtomicInteger integrations = new AtomicInteger(0);

        /**
         * Set when the same label is registered again with a different shape. The integrations
         * under the label then belong to two gatherers that cannot be told apart, and a verdict
         * about "the" gatherer's combiner would be drawn from the other one's threads.
         */
        volatile boolean shapeConflict;

        /** Thread id that first integrated each state object, keyed by the object's identity. */
        final Map<IdentityKey, Long> stateOwners = new ConcurrentHashMap<>();

        /** Claims the one shared-state report this gatherer may produce. */
        final java.util.concurrent.atomic.AtomicBoolean sharedStateReported =
                new java.util.concurrent.atomic.AtomicBoolean();

        /**
         * Claims the one report this gatherer is allowed to produce.
         *
         * <p>Replaces an equality test on {@code integratingThreadIds.size()}, which was a racy
         * read of a concurrently mutated set: when several workers are released together, the
         * thread whose add took the set to two can read it back as four or five, no other thread
         * sees two either, and the report is then never emitted at all. A claim flag reports once
         * per gatherer however the adds interleave.
         */
        final java.util.concurrent.atomic.AtomicBoolean reported =
                new java.util.concurrent.atomic.AtomicBoolean();

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
        // First registration wins, like SharedMessageDigestDetector and
        // DaemonThreadHygieneDetector. An unconditional put discarded the accumulated
        // integratingThreadIds, so a consumer registering the gatherer inside the concurrent
        // body - which is what an @AsyncTest body is - reset the evidence on every worker and
        // the "integrator ran on more than one thread" test could never reach two.
        GathererInfo existing = gatherers.putIfAbsent(name, new GathererInfo(hasCombiner, parallel));
        if (existing != null && (existing.hasCombiner != hasCombiner || existing.parallel != parallel)) {
            // Two gatherers under one label. Their integrations are indistinguishable from here,
            // so the label no longer supports a missing-combiner verdict. The state-identity
            // check is keyed by the state object, not the label, and is unaffected.
            existing.shapeConflict = true;
        }
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
        recordThread(name, info, thread);
    }

    /**
     * Record one integrator invocation against the state object it was handed.
     *
     * <p>This is the evidence a parallel gatherer <em>with</em> a combiner can be judged on. The
     * runtime gives every segment its own state from the initializer, so one state object
     * integrated on two threads means the segments are not confined: the initializer returned a
     * shared instance, or the integrator mutates captured state and passes it off as its own.
     * Without the state object the detector cannot tell that from the correct shape, and stays
     * silent on a gatherer that declares a combiner.
     *
     * @param name   the gatherer's registered name
     * @param state  the state object the integrator received (the initializer's result)
     * @param thread the thread running the integrator
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordIntegrate(String name, Object state, Thread thread) {
        if (name == null || thread == null) return;
        totalIntegrations.incrementAndGet();
        GathererInfo info = gatherers.get(name);
        if (info == null) return;
        recordThread(name, info, thread);
        // A gatherer with no combiner is evaluated sequentially, and its one state may pass
        // between threads legitimately; that shape is judged on threads, above.
        if (state == null || !info.parallel || !info.hasCombiner) return;

        if (info.stateOwners.size() >= MAX_TRACKED_STATES) {
            info.stateOwners.clear();
        }
        long tid = thread.threadId();
        Long owner = info.stateOwners.putIfAbsent(new IdentityKey(state), tid);
        if (owner != null && owner != tid && info.sharedStateReported.compareAndSet(false, true)) {
            sharedStateReports.add(
                "Gatherer '" + name + "': one state object (" + state.getClass().getSimpleName()
                + ") was integrated on two threads. On a parallel stream every segment gets its "
                + "own state from the initializer; a state seen on two threads is shared across "
                + "the split and races. Return a fresh object from the initializer and keep all "
                + "mutation inside it."
            );
        }
    }

    private void recordThread(String name, GathererInfo info, Thread thread) {
        info.integrations.incrementAndGet();
        boolean firstFromThisThread = info.integratingThreadIds.add(thread.threadId());

        // Only meaningful once we have evidence of multi-thread execution. "At least two, claimed
        // once" rather than "exactly two": size() is a racy read of a set the other workers are
        // adding to at that same moment, so an equality test can be missed by every thread at
        // once - which reports nothing under exactly the concurrency this detector exists for.
        //
        // Only a gatherer with no combiner is judged on threads alone. With a combiner, several
        // integrating threads is the parallel contract working (each segment on its own state),
        // so that shape is judged only on state identity, in the three-argument overload.
        if (firstFromThisThread && !info.hasCombiner && info.parallel && !info.shapeConflict
                && info.integratingThreadIds.size() >= 2
                && info.reported.compareAndSet(false, true)) {
            missingCombinerReports.add(
                "Gatherer '" + name + "': integrator ran on multiple threads but the gatherer "
                + "declares no combiner. On a parallel stream the per-thread states cannot be "
                + "merged — results are lost or non-deterministic. Add a combiner, or build it "
                + "with Gatherer.ofSequential(...) to force sequential evaluation."
            );
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

        /**
         * {@return true if any unsafe parallel-gatherer usage was detected}
         */
        public boolean hasIssues() {
            return !missingCombinerIssues.isEmpty() || !sharedStateIssues.isEmpty();
        }

        /**
         * {@return the missing combiner issues}
         */
        public List<String> getMissingCombinerIssues() { return Collections.unmodifiableList(missingCombinerIssues); }
        /**
         * {@return the shared state issues}
         */
        public List<String> getSharedStateIssues()     { return Collections.unmodifiableList(sharedStateIssues); }
        /**
         * {@return the total gatherers}
         */
        public int          getTotalGatherers()        { return totalGatherers; }
        /**
         * {@return the total integrations}
         */
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
                sb.append(IssueSeverity.HIGH.format())
                  .append(": Gatherer state shared across parallel segments (data race)\n");
            }

            sb.append("  Gatherers=").append(totalGatherers)
              .append(", Integrations=").append(totalIntegrations).append("\n");

            ReportSections.appendSection(sb, "Missing combiner on parallel stream (lost results)", missingCombinerIssues);
            ReportSections.appendSection(sb, "One state object integrated on several threads (shared across the split)", sharedStateIssues);

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
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
