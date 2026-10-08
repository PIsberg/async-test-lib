package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

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
 *   <li>A gatherer that should run in parallel supplies a {@code combiner} so the
 *       per-segment states can be merged. A {@code Gatherer.ofSequential(...)} (or any
 *       gatherer built without a combiner) is evaluated sequentially even on a parallel
 *       stream: the JDK integrates one state segment by segment, in encounter order, and
 *       loses nothing. JDK 24+ behaviour, pinned by this detector's test (#777).</li>
 *   <li>The integrator must only touch the per-element state object it is handed —
 *       never shared/captured mutable state — or parallel workers race on it.</li>
 * </ul>
 *
 * <p>The dangerous combination is a gatherer whose segments are not confined to their own
 * state: an initializer returning a shared instance, or an integrator mutating captured
 * state. The result is a silent data race: lost updates,
 * {@code ConcurrentModificationException}, or non-deterministic output.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>No combiner on a parallel stream</b> ({@code LOW}): the gatherer was declared
 *       parallel without a combiner and its integrator ran on more than one thread. The
 *       JDK hands its one state between threads in order, so results are correct, but the
 *       gather stage runs sequentially and the parallel stream buys it nothing.</li>
 *   <li><b>Shared-state race</b> ({@code HIGH}): one state object of a parallel gatherer that has a
 *       combiner was integrated on two threads, so the segments are not confined to their
 *       own state. This needs the state object, from
 *       {@link #recordIntegrate(String, Object, Thread)}; a gatherer with a combiner whose
 *       integrator merely runs on several threads is the correct parallel shape and is
 *       silent. For any gatherer, with or without a combiner, the same finding is drawn from
 *       time rather than threads when the integrator records
 *       {@link #recordIntegrateEnter(String, Object, Thread)} and
 *       {@link #recordIntegrateExit(String, Object)}: two integrations of one state in progress
 *       at once on two threads, which the JDK's in-order hand-off never produces.</li>
 * </ul>
 *
 * <p>Labels identify gatherers. Registering one label twice with different shapes marks it
 * ambiguous, and no missing-combiner verdict is drawn from its integrations.
 *
 * <p>The enter and exit pair is recorded by hand: the agent does not weave a gatherer's
 * integrator, which is a lambda the JDK's stream internals call. An enter whose exit was never
 * recorded is dropped when the next round starts (#846); within one round it cannot be told from
 * an overlap, so pair the two in a {@code finally} block.
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 8, includes = DetectorType.GATHERER_CONCURRENCY_MISUSE)
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

        /**
         * State objects some thread is inside an integration of right now, keyed by identity, from
         * the enter and exit records (#846).
         */
        final Map<IdentityKey, InIntegration> inIntegration = new ConcurrentHashMap<>();

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

    /**
     * One state object that a thread is inside an integration of. Mutated only inside the owning
     * map's {@code compute}, which serializes every change to one key.
     */
    private static final class InIntegration {
        /** The thread whose enter found the state idle and opened this entry. */
        final long threadId;

        /** Integrations of the state entered and not yet exited, on any thread. */
        private int open = 1;

        InIntegration(long threadId) {
            this.threadId = threadId;
        }

        InIntegration enter() {
            open++;
            return this;
        }

        /** {@return this entry, or null once no integration of the state is open, to remove it} */
        @Nullable InIntegration exit() {
            open--;
            return open == 0 ? null : this;
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

    /**
     * Record that an integrator invocation has started on {@code state}; pair it with
     * {@link #recordIntegrateExit(String, Object)} in a {@code finally} block.
     *
     * <p>Counts as one integration exactly like {@link #recordIntegrate(String, Object, Thread)},
     * so call this instead of it, not as well. What the pair adds is time: the JDK never runs two
     * integrations of one state at once, not even for a gatherer without a combiner, whose single
     * state it hands between threads in encounter order (#777). So when a second thread enters an
     * integration of a state another thread is still inside, the initializer handed that state to
     * several streams or segments at once, and they race on it. That is reported ({@code HIGH})
     * whatever the gatherer's registered shape. Thread identity alone cannot see this sharing:
     * for a combiner-less gatherer a state on two threads is the JDK's hand-off.
     *
     * <p>A state deliberately shared and itself thread-safe is reported too: the gatherer contract
     * is one state per initializer call.
     *
     * @param name   the gatherer's registered name; an unregistered one is counted and not judged
     * @param state  the state object the integrator received; null skips the overlap check
     * @param thread the thread running the integrator; null records nothing
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordIntegrateEnter(String name, Object state, Thread thread) {
        recordIntegrate(name, state, thread);
        if (name == null || state == null || thread == null) return;
        GathererInfo info = gatherers.get(name);
        if (info == null) return;

        if (info.inIntegration.size() >= MAX_TRACKED_STATES) {
            // Only a missing exit leaves an entry behind. Dropping entries can miss an overlap
            // that straddles the clear, never invent one.
            info.inIntegration.clear();
        }
        long tid = thread.threadId();
        InIntegration inside = info.inIntegration.compute(IdentityKey.lookup(state),
            (key, current) -> current == null ? new InIntegration(tid) : current.enter());
        // The entry names the thread that opened this stretch of integrations. Until two threads
        // have overlapped only one thread is ever inside, so a different name here is the first
        // overlap; after that the gatherer has already claimed its one report.
        if (inside.threadId != tid && info.sharedStateReported.compareAndSet(false, true)) {
            sharedStateReports.add(
                "Gatherer '" + name + "': two integrations of one state object ("
                + state.getClass().getSimpleName() + ") were in progress at the same time on two "
                + "threads. The JDK never integrates one state concurrently, even when it hands a "
                + "gatherer's single state between threads, so the initializer gave that object to "
                + "several streams or segments at once and they race on it. Return a fresh object "
                + "from the initializer and keep all mutation inside it."
            );
        }
    }

    /**
     * Record that the integrator invocation {@link #recordIntegrateEnter(String, Object, Thread)}
     * opened on {@code state} has returned or thrown. An exit with no open enter is ignored.
     *
     * @param name  the gatherer's registered name, as passed to the enter
     * @param state the state object passed to the enter; null records nothing
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordIntegrateExit(String name, Object state) {
        if (name == null || state == null) return;
        GathererInfo info = gatherers.get(name);
        if (info == null) return;
        info.inIntegration.computeIfPresent(IdentityKey.lookup(state), (key, current) -> current.exit());
    }

    /**
     * Marks the start of a new round: every integration still open is dropped (#846).
     *
     * <p>Rounds run one after another, so an integration open when the next round starts never
     * returned through {@link #recordIntegrateExit(String, Object)}; its enter was not paired in a
     * {@code finally}. Kept, it would read as an overlap with the next thread to enter that state.
     * An exit missing within one round still cannot be told from an overlap.
     *
     * @since 1.12.4
     */
    public void markInvocationStart() {
        for (GathererInfo info : gatherers.values()) {
            info.inIntegration.clear();
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
        //
        // What that shape costs is parallelism, not results: the JDK evaluates a gatherer with no
        // combiner sequentially on a parallel stream, one state handed from segment to segment in
        // encounter order (GathererOp's Hybrid path, pinned on JDK 24+ by the test, #777). Hence
        // a LOW finding that says so, rather than the "results are lost" it used to claim.
        if (firstFromThisThread && !info.hasCombiner && info.parallel && !info.shapeConflict
                && info.integratingThreadIds.size() >= 2
                && info.reported.compareAndSet(false, true)) {
            missingCombinerReports.add(
                "Gatherer '" + name + "': declared parallel with no combiner, and its integrator "
                + "ran on several threads. The JDK evaluates a gatherer without a combiner "
                + "sequentially even on a parallel stream: one state, handed between threads in "
                + "encounter order, so no result is dropped, but this stage gets no parallel "
                + "speedup. Add a combiner if the stage should run in parallel; otherwise the "
                + ".parallel() buys nothing here."
            );
        }
    }

    /**
     * Analyze all recorded gatherer events for unsafe parallel usage.
     *
     * @return a report describing detected issues
     */
    public GathererConcurrencyMisuseReport analyze() {
        GathererConcurrencyMisuseReport report801 = new GathererConcurrencyMisuseReport(
            new ArrayList<>(missingCombinerReports),
            new ArrayList<>(sharedStateReports),
            gatherers.size(),
            totalIntegrations.get()
        );
        report801.fillStructuredViolations();
        return DetectorFailurePolicy.checkedReport(this, report801);
    }

    /**
     * Report of Gatherer concurrency-misuse analysis.
     */
    public static class GathererConcurrencyMisuseReport {
        private final List<String> missingCombinerIssues;
        private final List<String> sharedStateIssues;
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a Violation per finding (#801); called once by the detector before it returns the report. */
        void fillStructuredViolations() {
            if (!hasIssues()) {
                return;
            }
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(toString()).orElse(IssueSeverity.HIGH);
                for (String finding : missingCombinerIssues) {
                    structuredViolations.add(new Violation("GathererConcurrencyMisuse", severity,
                            finding, List.of(), Map.of(), Instant.now()));
                }
                for (String finding : sharedStateIssues) {
                    structuredViolations.add(new Violation("GathererConcurrencyMisuse", severity,
                            finding, List.of(), Map.of(), Instant.now()));
                }
        }

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
         * {@return true if either finding was recorded: a combiner-less gatherer on a parallel
         * stream ({@code LOW}, a missed speedup) or one state shared across segments
         * ({@code HIGH}, a data race)}
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

            if (!sharedStateIssues.isEmpty()) {
                sb.append(IssueSeverity.HIGH.format())
                  .append(": Gatherer state shared across parallel segments (data race)\n");
            } else {
                sb.append(IssueSeverity.LOW.format())
                  .append(": Gatherer without a combiner on a parallel stream (evaluated sequentially)\n");
            }

            sb.append("  Gatherers=").append(totalGatherers)
              .append(", Integrations=").append(totalIntegrations).append("\n");

            ReportSections.appendSection(sb, "No combiner on a parallel stream (the gather stage runs sequentially)", missingCombinerIssues);
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

                On a PARALLEL stream, with a COMBINER the runtime:
                  1. splits the input,
                  2. runs the integrator on independent state per segment,
                  3. merges those states with the combiner.
                Without a combiner it evaluates the gather stage sequentially: one state,
                integrated segment by segment in encounter order. Correct, but not parallel.

                Correct usage:
                  // Stateful and meant to run in parallel → provide a combiner:
                  Gatherer.of(initializer, integrator, combiner, finisher);

                  // Stateful with no safe merge → sequential, even on a parallel stream:
                  Gatherer.ofSequential(initializer, integrator, finisher);

                Common mistakes:
                  ✗ Integrator mutating captured/shared state instead of its private state
                    object → data race across the split (lost updates, CME)
                  ~ No combiner on a parallel stream → nothing is lost, but the stage runs
                    sequentially and .parallel() buys it nothing

                Rule of thumb: keep all mutation inside the state from the initializer, and
                supply a combiner when the stage should actually run in parallel.
                """;
        }
    }
}
