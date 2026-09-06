package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects misuse of the lazy collections JEP 526 (Lazy Constants, second preview in JDK 26) added
 * alongside {@code LazyConstant}: {@code List.ofLazy(size, fn)} and {@code Map.ofLazy(keys, fn)}.
 *
 * <p>{@link LazyConstantMisuseDetector} covers one holder with one supplier. A lazy collection is a
 * different shape of problem: it is <em>n</em> independent at-most-once computations sharing one
 * mapping function, computed on whichever thread first asks for that element.
 *
 * <pre>{@code
 * static final List<Board> BOARDS = List.ofLazy(64, Renderer::render);
 * // element 7 is computed on the first thread to call BOARDS.get(7), and only ever once
 * }</pre>
 *
 * <p>The interesting failure is one a single {@code LazyConstant} cannot have. Because the elements
 * compute independently and concurrently, a mapping function that reaches back into its own
 * collection creates a dependency between two elements - and if the dependency runs both ways, two
 * threads each hold one element's computation while waiting for the other's. That is a deadlock the
 * JDK breaks with {@code IllegalStateException} when it can see the cycle on one thread, and does
 * not break when the cycle is spread across two.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Circular element dependency</b> - the mapping functions form a cycle (element A's
 *       computation reads B, B's reads A). Self-deadlock on one thread; a real deadlock across
 *       two.</li>
 *   <li><b>Self-reentrant element</b> - an element's mapping function read that same element while
 *       it was still computing. The JDK throws {@code IllegalStateException} to break it.</li>
 *   <li><b>Mapping function ran more than once</b> - one element computed twice. The real API
 *       guarantees at-most-once per element, so this is a hand-rolled lazy array that lost the
 *       race.</li>
 *   <li><b>Non-deterministic mapping function</b> - two computations of the same element produced
 *       values that are not {@code equals()}. Which one the collection ends up holding is decided
 *       by thread timing.</li>
 *   <li><b>Null-producing mapping function</b> - an element computed to {@code null}, which JDK 26
 *       rejects with {@code NullPointerException}.</li>
 *   <li><b>Nested lazy computation</b> (warning) - an element's mapping function computed another
 *       element of the same collection without forming a cycle. Correct, but the computing thread
 *       holds one element while blocking on another, so contention on the inner element serialises
 *       the outer one too.</li>
 *   <li><b>Compute convoy</b> (warning) - at least {@value #DEFAULT_CONVOY_THRESHOLD} distinct
 *       threads waited on one element while a single thread computed it.</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.lazyCollectionMisuseDetector();
 *
 * d.recordGet("BOARDS", 7, Thread.currentThread());
 * // inside the mapping function, for the element being computed:
 * d.recordComputeStart("BOARDS", 7, Thread.currentThread());
 * Board b = render(7);
 * d.recordComputeEnd("BOARDS", 7, Thread.currentThread(), b);
 * }</pre>
 *
 * @since 1.9.7
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "One state object per collection name and element key in a ConcurrentHashMap; counters are "
             + "atomics and waiter sets are concurrent. The per-thread stack of in-flight computations is an "
             + "ArrayDeque per thread id, so each is touched by exactly one thread and needs no synchronisation. "
             + "Dependency edges accumulate in a synchronized LinkedHashSet and are walked once in analyze(), "
             + "after the run has quiesced.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/LazyCollectionMisuseDetectorTest.java"
)
public final class LazyCollectionMisuseDetector {

    /**
     * Distinct threads waiting on one element at which the wait counts as a convoy.
     *
     * <p>One thread computing while another waits is what lazy initialisation is for. Four waiting
     * on a single element means the mapping function is the workload's bottleneck.
     */
    public static final int DEFAULT_CONVOY_THRESHOLD = 4;

    /** A collection element: the collection's name and the element's key or index. */
    private record Element(String collection, String key) {
        @Override public String toString() { return collection + "[" + key + "]"; }
    }

    private static final class ElementState {
        final Element       element;
        final AtomicInteger computeEnds   = new AtomicInteger();
        final AtomicInteger gets          = new AtomicInteger();
        final AtomicInteger nullValues    = new AtomicInteger();
        final AtomicInteger selfReentries = new AtomicInteger();
        /** Threads that asked for the element while another thread was computing it. */
        final Set<Long>     waiters       = ConcurrentHashMap.newKeySet();
        /** Threads currently inside this element's mapping function. */
        final Set<Long>     computing     = ConcurrentHashMap.newKeySet();
        /** Completed values, kept to compare successive computations for determinism. */
        private final List<Object> values = new ArrayList<>();
        /** Guards {@link #values}. A private lock, so nothing outside can hold it. */
        private final Object valuesLock   = new Object();

        ElementState(Element element) { this.element = element; }

        void addValue(@Nullable Object value) {
            synchronized (valuesLock) { values.add(value); }
        }

        boolean valuesDisagree() {
            synchronized (valuesLock) {
                if (values.size() < 2) return false;
                Object first = values.get(0);
                for (Object v : values) {
                    if (!Objects.equals(first, v)) return true;
                }
                return false;
            }
        }
    }

    private final Map<Element, ElementState> elements = new ConcurrentHashMap<>();
    /** Directed edges outer to inner: computing the outer element caused the inner one to compute. */
    private final Set<Map.Entry<Element, Element>> dependencies = new LinkedHashSet<>();
    /**
     * Per-thread stack of computations currently in flight, keyed by thread id rather than held
     * in a {@code ThreadLocal}. Each deque is still touched by exactly one thread, so it needs no
     * synchronisation of its own; keying by id is what lets {@link #markInvocationStart()} drop a
     * stack a thrown mapping function abandoned, which a {@code ThreadLocal} cannot do from the
     * runner's thread (#498).
     */
    private final Map<Long, Deque<Element>> inFlight = new ConcurrentHashMap<>();
    private Deque<Element> inFlightStack() {
        return inFlight.computeIfAbsent(Thread.currentThread().threadId(), k -> new ArrayDeque<>());
    }

    /**
     * Clears the per-thread in-flight state left over from the previous invocation round.
     *
     * <p>Called by {@code ConcurrencyRunner} before each round, after the previous round's
     * workers have all finished, so nothing is legitimately in flight when it runs.
     *
     * <p>{@code recordComputeStart} and {@code recordComputeEnd} are paired, and a mapping
     * function that throws without a {@code finally} leaves its element on the thread's stack and
     * its id in the element's computing set. Platform worker threads are pooled, so the next
     * round's computation of that element on the same thread read as a self re-entry, and every
     * other thread's get() on it counted as a waiter feeding the convoy finding (#498).
     *
     * @since 1.11.2
     */
    public void markInvocationStart() {
        inFlight.clear();
        for (ElementState s : elements.values()) {
            s.computing.clear();
        }
    }

    private final int        convoyThreshold;
    private volatile boolean enabled = true;

    /** Creates a detector with the default convoy threshold. */
    public LazyCollectionMisuseDetector() {
        this(DEFAULT_CONVOY_THRESHOLD);
    }

    /**
     * Creates a detector with an explicit convoy threshold.
     *
     * @param convoyThreshold distinct threads waiting on one element at which the wait is reported;
     *                        values below 2 are raised to 2, since one waiter is not a convoy
     */
    public LazyCollectionMisuseDetector(int convoyThreshold) {
        this.convoyThreshold = Math.max(convoyThreshold, 2);
    }

    /**
     * Record a read of one element of a lazy collection.
     *
     * @param collection the collection's name, as it should appear in the report
     * @param key        the element's index (for {@code List.ofLazy}) or key (for {@code Map.ofLazy})
     * @param thread     the reading thread
     */
    public void recordGet(String collection, Object key, Thread thread) {
        ElementState s = stateOrCreate(collection, key);
        if (s == null || thread == null) return;
        s.gets.incrementAndGet();
        long id = thread.threadId();
        if (!s.computing.isEmpty() && !s.computing.contains(id)) s.waiters.add(id);
    }

    /**
     * Record entry into the mapping function for one element.
     *
     * <p>Pair this with {@code recordComputeEnd} in a {@code finally}. A supplier or mapping
     * function that throws past an unpaired start leaves the entry in flight, and every later
     * record on this thread then reads as re-entrancy. {@link #markInvocationStart()} bounds
     * that to the round it happened in; only a {@code finally} prevents it inside one.
     *
     * @param collection the collection's name
     * @param key        the element's index or key
     * @param thread     the computing thread
     */
    public void recordComputeStart(String collection, Object key, Thread thread) {
        ElementState s = stateOrCreate(collection, key);
        if (s == null || thread == null) return;

        Deque<Element> stack = inFlightStack();
        if (stack.contains(s.element)) {
            s.selfReentries.incrementAndGet();
        } else if (!stack.isEmpty()) {
            Element outer = stack.peek();
            if (outer.collection().equals(s.element.collection())) {
                synchronized (dependencies) {
                    dependencies.add(Map.entry(outer, s.element));
                }
            }
        }
        stack.push(s.element);
        s.computing.add(thread.threadId());
    }

    /**
     * Record the return from the mapping function for one element.
     *
     * @param collection the collection's name
     * @param key        the element's index or key
     * @param thread     the computing thread
     * @param value      the value the mapping function produced, which JDK 26 rejects if null
     */
    public void recordComputeEnd(String collection, Object key, Thread thread, @Nullable Object value) {
        ElementState s = stateOrCreate(collection, key);
        if (s == null || thread == null) return;

        Deque<Element> stack = inFlightStack();
        stack.remove(s.element);
        s.computing.remove(thread.threadId());
        s.computeEnds.incrementAndGet();
        if (value == null) s.nullValues.incrementAndGet();
        s.addValue(value);
    }

    private @Nullable ElementState stateOrCreate(String collection, Object key) {
        if (!enabled || collection == null) return null;
        Element e = new Element(collection, String.valueOf(key));
        return elements.computeIfAbsent(e, ElementState::new);
    }

    /** Turn recording off; already-recorded state is kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded element computations and builds the report.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();

        List<ElementState> all = new ArrayList<>(elements.values());
        all.sort((a, b) -> a.element.toString().compareTo(b.element.toString()));

        for (ElementState s : all) {
            selfReentrant(r, s);
            computedMoreThanOnce(r, s);
            nonDeterministic(r, s);
            nullValue(r, s);
            convoy(r, s, convoyThreshold);
        }
        dependencyFindings(r);
        return r;
    }

    private static void selfReentrant(Report r, ElementState s) {
        int reentries = s.selfReentries.get();
        if (reentries < 1) return;
        String msg = String.format(
                "Element %s re-entered its own mapping function %d time(s) on the same thread. The lazy "
                + "collection cannot supply a value that is still being computed, so JDK 26 throws "
                + "IllegalStateException to break the recursion.",
                s.element, reentries);
        r.add("LazyCollectionMisuse", IssueSeverity.CRITICAL, msg,
                Map.of("element", s.element.toString(), "issue", "selfReentrantElement",
                       "reentries", reentries));
    }

    private static void computedMoreThanOnce(Report r, ElementState s) {
        int ends = s.computeEnds.get();
        if (ends < 2) return;
        String msg = String.format(
                "Element %s was computed %d times across %d read(s). List.ofLazy and Map.ofLazy run each "
                + "element's mapping function at most once, so more than one completion means the value is "
                + "coming from a hand-rolled holder that lost the race rather than from the lazy collection.",
                s.element, ends, s.gets.get());
        r.add("LazyCollectionMisuse", IssueSeverity.HIGH, msg,
                Map.of("element", s.element.toString(), "issue", "elementComputedMoreThanOnce",
                       "computations", ends, "gets", s.gets.get()));
    }

    private static void nonDeterministic(Report r, ElementState s) {
        if (!s.valuesDisagree()) return;
        String msg = String.format(
                "Element %s produced values that are not equal across its %d computations. Which value the "
                + "collection ends up holding is then decided by which thread got there first; a lazy "
                + "collection's mapping function has to be a pure function of the key.",
                s.element, s.computeEnds.get());
        r.add("LazyCollectionMisuse", IssueSeverity.HIGH, msg,
                Map.of("element", s.element.toString(), "issue", "nonDeterministicMappingFunction",
                       "computations", s.computeEnds.get()));
    }

    private static void nullValue(Report r, ElementState s) {
        int nulls = s.nullValues.get();
        if (nulls < 1) return;
        String msg = String.format(
                "Element %s computed to null %d time(s). JDK 26 lazy collections do not hold null - the "
                + "mapping function returning one throws NullPointerException at the call that triggered it, "
                + "which is whichever thread happened to read the element first.",
                s.element, nulls);
        r.add("LazyCollectionMisuse", IssueSeverity.HIGH, msg,
                Map.of("element", s.element.toString(), "issue", "nullProducingMappingFunction",
                       "nullComputations", nulls));
    }

    private static void convoy(Report r, ElementState s, int threshold) {
        int waiters = s.waiters.size();
        if (waiters < threshold) return;
        String msg = String.format(
                "Element %s had %d thread(s) waiting on it while one thread ran its mapping function. The "
                + "at-most-once guarantee holds, and it holds by making everyone else wait, so a slow element "
                + "is a barrier every reader of it queues behind.",
                s.element, waiters);
        r.add("LazyCollectionMisuse", IssueSeverity.LOW, msg,
                Map.of("element", s.element.toString(), "issue", "computeConvoy",
                       "waiters", waiters, "gets", s.gets.get()));
    }

    private void dependencyFindings(Report r) {
        Map<Element, Set<Element>> graph = new LinkedHashMap<>();
        synchronized (dependencies) {
            for (Map.Entry<Element, Element> edge : dependencies) {
                graph.computeIfAbsent(edge.getKey(), k -> new LinkedHashSet<>()).add(edge.getValue());
            }
        }
        if (graph.isEmpty()) return;

        Set<String> cycles = new TreeSet<>();
        for (Element start : graph.keySet()) {
            findCycle(graph, start, start, new LinkedHashSet<>(), new ArrayList<>(), cycles);
        }

        if (!cycles.isEmpty()) {
            String cycleList = String.join("; ", cycles);
            String msg = String.format(
                    "Mapping functions in a lazy collection depend on each other in a cycle: %s. One thread "
                    + "walking the cycle self-deadlocks and JDK 26 breaks it with IllegalStateException; two "
                    + "threads entering it from opposite ends each hold one element and wait for the other, "
                    + "which nothing breaks.",
                    cycleList);
            r.add("LazyCollectionMisuse", IssueSeverity.CRITICAL, msg,
                    Map.of("issue", "circularElementDependency", "cycles", cycleList));
            return;
        }

        SortedSet<String> nested = new TreeSet<>();
        for (Map.Entry<Element, Set<Element>> e : graph.entrySet()) {
            for (Element inner : e.getValue()) nested.add(e.getKey() + " -> " + inner);
        }
        String edgeList = String.join(", ", nested);
        String msg = String.format(
                "An element's mapping function computed another element of the same collection: %s. No cycle, "
                + "so it terminates, but the outer element's computation is held open for the whole of the "
                + "inner one - every thread waiting on the outer element waits for both.",
                edgeList);
        r.add("LazyCollectionMisuse", IssueSeverity.LOW, msg,
                Map.of("issue", "nestedLazyComputation", "edges", edgeList, "edgeCount", nested.size()));
    }

    /** Depth-first walk looking for a path from {@code current} back to {@code target}. */
    private static void findCycle(Map<Element, Set<Element>> graph, Element target, Element current,
                                  Set<Element> visited, List<Element> path, Set<String> cycles) {
        if (!visited.add(current)) return;
        path.add(current);
        for (Element next : graph.getOrDefault(current, Set.of())) {
            if (next.equals(target)) {
                List<String> loop = new ArrayList<>();
                for (Element e : path) loop.add(e.toString());
                loop.add(target.toString());
                cycles.add(String.join(" -> ", loop));
            } else {
                findCycle(graph, target, next, visited, path, cycles);
            }
        }
        path.remove(path.size() - 1);
        visited.remove(current);
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        void add(String detector, IssueSeverity severity, String message, Map<String, Object> context) {
            // The text is what the failOn gate classifies, so each line carries its own label.
            violations.add(severity.getLabel() + " " + message);
            structuredViolations.add(
                    new Violation(detector, severity, message, List.of(), context, Instant.now()));
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "LAZY COLLECTION MISUSE - clean";
            StringBuilder sb = new StringBuilder("LAZY COLLECTION MISUSE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: List.ofLazy and Map.ofLazy are n independent at-most-once computations sharing one\n")
              .append("       mapping function, each running on whichever thread asked for that element first.\n")
              .append("       The guarantee is per element, not per collection, so elements can compute at the\n")
              .append("       same time - and a mapping function that reads its own collection couples them.\n")
              .append("  Fix:\n")
              .append("    - Make the mapping function a pure function of the key: same key, same value, no\n")
              .append("      reads of shared mutable state and no reads of the collection it belongs to\n")
              .append("    - Where elements genuinely derive from each other, compute the base values eagerly and\n")
              .append("      keep the lazy layer one level deep, so no element can wait on another\n")
              .append("    - Never return null from the mapping function; JDK 26 rejects it\n")
              .append("    - Move slow work out of the mapping function if many readers queue on one element\n");
            return sb.toString();
        }
    }
}
