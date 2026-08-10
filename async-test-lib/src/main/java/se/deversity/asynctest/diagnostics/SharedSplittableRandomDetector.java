package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Detects {@link java.util.SplittableRandom} and JEP 356 {@link RandomGenerator} instances
 * (L64X128MixRandom, Xoshiro256PlusPlus, …) shared across threads. These generators are
 * documented <em>not thread-safe</em>: their state transition is a plain, non-atomic
 * read-modify-write, so concurrent {@code nextLong()} calls interleave it — duplicated values,
 * broken statistical guarantees, no exception. The failure is silent, which is what separates
 * this from {@code SHARED_RANDOM}: {@code java.util.Random} is thread-safe but contended, these
 * are simply corrupted.
 *
 * <p>{@code java.util.Random} and its subclasses are deliberately out of scope here —
 * {@code Random} belongs to {@code SHARED_RANDOM}, {@code SecureRandom} to
 * {@code SHARED_SECURE_RANDOM}, and {@code ThreadLocalRandom} to
 * {@code THREAD_LOCAL_RANDOM_MISUSE}. One {@code instanceof Random} check excludes all three.
 *
 * <p>Like the other Shared* detectors, this one observes sharing, not locks — an instance guarded
 * by external synchronization is flagged all the same; treat a finding as a prompt to verify the
 * sharing is intended, or to {@code split()} per thread.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectSharedSplittableRandom = true)
 * void testGeneratorSharing() {
 *     var d = AsyncTestContext.sharedSplittableRandomDetector();
 *     d.registerGenerator(splittable, "ids");
 *     long v = splittable.nextLong();
 *     d.recordAccess(splittable, "ids", "nextLong");
 * }
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; "
        + "thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedSplittableRandomDetectorTest.java"
)
public final class SharedSplittableRandomDetector {

    private static final class GeneratorState {
        final String name;
        final String type;
        final AtomicInteger accessCount = new AtomicInteger();
        final Set<String> operations = ConcurrentHashMap.newKeySet();
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        GeneratorState(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private final Map<Integer, GeneratorState> generators = new ConcurrentHashMap<>();

    /**
     * Register a generator for monitoring. {@code java.util.Random} subclasses are ignored —
     * they belong to the dedicated detectors named in the class Javadoc.
     *
     * @param generator the generator to monitor (null-safe)
     * @param name      a descriptive name for reporting (may be {@code null})
     */
    public void registerGenerator(RandomGenerator generator, String name) {
        if (!tracked(generator)) {
            return;
        }
        int id = System.identityHashCode(generator);
        if (generators.containsKey(id)) {
            return;
        }
        String label = name != null ? name : generator.getClass().getSimpleName() + "@" + id;
        generators.computeIfAbsent(id, k -> new GeneratorState(label, generator.getClass().getSimpleName()));
    }

    /**
     * Record a generator access from the calling thread. Auto-registers unknown instances.
     *
     * @param generator  the generator instance (null-safe)
     * @param name       the generator name (should match registration; may be {@code null})
     * @param methodName the method called ({@code nextLong}, {@code nextInt}, {@code split}, …)
     */
    public void recordAccess(RandomGenerator generator, String name, String methodName) {
        if (!tracked(generator)) {
            return;
        }
        int id = System.identityHashCode(generator);
        GeneratorState state = generators.get(id);
        if (state == null) {
            final String label = name != null ? name : generator.getClass().getSimpleName() + "@" + id;
            final String type = generator.getClass().getSimpleName();
            state = generators.computeIfAbsent(id, k -> new GeneratorState(label, type));
        }
        state.accessCount.incrementAndGet();
        state.operations.add(methodName != null ? methodName : "next*");
        Thread current = Thread.currentThread();
        state.accessingThreadIds.add(current.threadId());
        state.accessingThreadNames.add(current.getName());
    }

    /** Everything except {@code java.util.Random} subclasses (see class Javadoc). */
    private static boolean tracked(RandomGenerator generator) {
        return generator != null && !(generator instanceof java.util.Random);
    }

    /**
     * Evaluate the observed state and produce a report. Must be idempotent:
     * calling it N times on quiescent state yields N identical reports.
     */
    public Report analyze() {
        Report r = new Report();
        for (GeneratorState state : generators.values()) {
            if (state.accessingThreadIds.size() <= 1) {
                continue;
            }
            String msg = String.format(
                    "'%s' (%s): accessed from %d threads (%s) via %s — not thread-safe; concurrent use"
                            + " silently corrupts the sequence (the detector observes sharing, not locks —"
                            + " verify external synchronization or split() per thread)",
                    state.name,
                    state.type,
                    state.accessingThreadIds.size(),
                    String.join(", ", state.accessingThreadNames),
                    String.join(", ", state.operations));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedSplittableRandom",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", state.name,
                            "generatorType", state.type,
                            "threadCount", state.accessingThreadIds.size(),
                            "accessCount", state.accessCount.get()),
                    Instant.now()));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report {
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SharedSplittableRandom — clean";
            StringBuilder sb = new StringBuilder("SHARED SPLITTABLE RANDOM DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("""
                      Why: SplittableRandom and the java.util.random generators update their state with plain
                           non-atomic writes. Concurrent nextX() calls interleave that transition: duplicated
                           values and broken statistical guarantees, with no exception to warn you. Unlike
                           java.util.Random — thread-safe but contended — these are simply corrupted.
                      Fix:
                        - split() (or splits()) and hand each thread its own generator — the designed use
                        - ThreadLocalRandom.current() for throwaway randomness
                        - one RandomGeneratorFactory-created instance per thread for reproducible streams
                      """);
            return sb.toString();
        }
    }
}
