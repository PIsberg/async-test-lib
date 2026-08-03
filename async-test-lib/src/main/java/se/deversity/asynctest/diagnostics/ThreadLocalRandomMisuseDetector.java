package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Detects misuse of {@link ThreadLocalRandom}: caching the reference returned by
 * {@link ThreadLocalRandom#current()} and using it from a different thread.
 *
 * <p><strong>Why it matters.</strong> {@code ThreadLocalRandom.current()} returns
 * the current thread's instance, and the whole point of the class is that each
 * thread draws from its own generator with no shared state or contention. The
 * returned reference must be used <em>only</em> by the thread that obtained it and
 * <em>only</em> for that call site — it must never be stored in a field and reused:
 *
 * <pre>{@code
 * // BUG: captured once, shared by every thread
 * private final Random rng = ThreadLocalRandom.current();
 * }</pre>
 *
 * <p>Sharing the instance defeats the per-thread isolation: multiple threads then
 * advance the same generator concurrently, reintroducing exactly the contention
 * and (because {@code ThreadLocalRandom} omits the synchronization that
 * {@code java.util.Random} has) the state-corruption / biased-output hazards the
 * class was designed to avoid. Distinct from {@link SharedRandomDetector}
 * ({@code java.util.Random}) and {@link SharedSecureRandomDetector}.
 *
 * <p>Cooperative API: report where the reference was obtained via
 * {@link #recordObtain} and each subsequent use via {@link #recordUse}. A
 * violation is flagged when a use occurs on a thread other than the one that
 * obtained the cached reference.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new ThreadLocalRandomMisuseDetector();
 * ThreadLocalRandom rng = ThreadLocalRandom.current();
 * d.recordObtain(rng, "cached-rng", Thread.currentThread());
 * // ...later, possibly on another thread:
 * d.recordUse(rng, Thread.currentThread());
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; misusing-thread sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ThreadLocalRandomMisuseDetectorTest.java"
)
public final class ThreadLocalRandomMisuseDetector {

    private static final class State {
        final String label;
        final long obtainingThreadId;
        final String obtainingThreadName;
        final java.util.Set<String> misusingThreads = ConcurrentHashMap.newKeySet();

        State(String label, long obtainingThreadId, String obtainingThreadName) {
            this.label = label;
            this.obtainingThreadId = obtainingThreadId;
            this.obtainingThreadName = obtainingThreadName;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record the thread that obtained a {@link ThreadLocalRandom} reference via
     * {@code current()} and cached it.
     *
     * @param rng    the obtained instance (null-safe)
     * @param name   descriptive label for reports (may be {@code null})
     * @param thread the thread that called {@code current()}
     */
    public void recordObtain(ThreadLocalRandom rng, String name, Thread thread) {
        if (rng == null || thread == null) return;
        int id = System.identityHashCode(rng);
        final String label = (name != null) ? name : "ThreadLocalRandom@" + id;
        instances.computeIfAbsent(id, k -> new State(label, thread.threadId(), thread.getName()));
    }

    /**
     * Record a use of a previously-obtained {@link ThreadLocalRandom} reference.
     * If {@code thread} differs from the obtaining thread, it is recorded as misuse.
     *
     * @param rng    the instance being used (null-safe)
     * @param thread the thread using it
     */
    public void recordUse(ThreadLocalRandom rng, Thread thread) {
        if (rng == null || thread == null) return;
        State s = instances.get(System.identityHashCode(rng));
        if (s == null) return; // never recorded as obtained — nothing to correlate
        if (thread.threadId() != s.obtainingThreadId) {
            s.misusingThreads.add(thread.getName());
        }
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.misusingThreads.isEmpty()) continue;
            String msg = String.format(
                    "ThreadLocalRandom '%s' obtained by thread '%s' but used by %d other thread(s) (%s) — "
                            + "the current() reference is per-thread and must not be cached and shared; "
                            + "doing so corrupts its state and biases output.",
                    s.label,
                    s.obtainingThreadName,
                    s.misusingThreads.size(),
                    String.join(", ", s.misusingThreads));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "ThreadLocalRandomMisuse",
                    IssueSeverity.MEDIUM,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "obtainingThread", s.obtainingThreadName,
                            "misusingThreadCount", s.misusingThreads.size()),
                    Instant.now()));
        }
        return r;
    }

    public static final class Report {
        /** The violations. */
        public final List<String> violations = new ArrayList<>();
        /** The structured violations. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** {@return whether there are issues} */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "THREADLOCALRANDOM MISUSE — clean";
            StringBuilder sb = new StringBuilder("THREADLOCALRANDOM MISUSE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Never store ThreadLocalRandom.current() in a field.\n")
              .append("    - Call ThreadLocalRandom.current() afresh on each thread, at each use site.\n");
            return sb.toString();
        }
    }
}
