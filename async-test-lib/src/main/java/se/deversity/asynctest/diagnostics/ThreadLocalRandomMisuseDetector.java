package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>What actually goes wrong, from the JDK source (JDK 8 onwards, read in 21 and 26):
 * {@code current()} returns one JVM-wide instance ({@code private static final
 * ThreadLocalRandom instance}), and before returning it calls {@code localInit()} when the
 * calling thread's probe is zero, which seeds that thread. The generator methods keep no state
 * in the object: {@code nextSeed()} reads and writes {@code Thread.currentThread()}'s own seed
 * field. So a captured reference used on another thread does not share a generator; it draws
 * from the using thread's seed, which nobody initialized unless that thread called
 * {@code current()} itself, and its sequence is then set by the thread id rather than seeded.
 * The javadoc of {@code current()} states the rule: its methods "should be called only by the
 * current thread, not by other threads". Distinct from {@link SharedRandomDetector}
 * ({@code java.util.Random}) and {@link SharedSecureRandomDetector}.
 *
 * <p>Because every thread gets the same object, identity cannot tell a correct per-thread
 * {@code current()} from a captured one. The model is therefore per thread: a use is misuse when
 * the using thread never recorded an obtain of its own.
 *
 * <p>Cooperative API: report where the reference was obtained via
 * {@link #recordObtain} and each subsequent use via {@link #recordUse}. A
 * violation is flagged when a use occurs on a thread that never recorded obtaining the
 * reference itself.
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
        /** The first thread to obtain the reference, named in the report. */
        final String obtainingThreadName;
        /** Every thread that called current() itself; a use on any of them is the idiom. */
        final java.util.Set<Long> obtainingThreadIds = ConcurrentHashMap.newKeySet();
        final java.util.Set<String> misusingThreads = ConcurrentHashMap.newKeySet();

        State(String label, String obtainingThreadName) {
            this.label = label;
            this.obtainingThreadName = obtainingThreadName;
        }
    }

    private final Map<IdentityKey, State> instances = new ConcurrentHashMap<>();

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
        IdentityKey key = new IdentityKey(rng);
        int id = key.hashCode();
        final String label = (name != null) ? name : "ThreadLocalRandom@" + id;
        instances.computeIfAbsent(key, k -> new State(label, ReportSections.threadLabel(thread)))
                .obtainingThreadIds.add(thread.threadId());
    }

    /**
     * Record a use of a previously-obtained {@link ThreadLocalRandom} reference.
     * If {@code thread} never recorded an obtain of its own, it is recorded as misuse.
     *
     * @param rng    the instance being used (null-safe)
     * @param thread the thread using it
     */
    public void recordUse(ThreadLocalRandom rng, Thread thread) {
        if (rng == null || thread == null) return;
        State s = instances.get(new IdentityKey(rng));
        if (s == null) return; // never recorded as obtained — nothing to correlate
        if (!s.obtainingThreadIds.contains(thread.threadId())) {
            s.misusingThreads.add(ReportSections.threadLabel(thread));
        }
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.misusingThreads.isEmpty()) continue;
            String msg = String.format(Locale.ROOT,
                    "ThreadLocalRandom '%s' obtained by thread '%s' but used by %d thread(s) that "
                            + "never called current() themselves (%s). The reference is per-thread: "
                            + "current() seeds the calling thread, and on a thread that skipped it "
                            + "the sequence is set by the thread id instead of seeded.",
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
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link se.deversity.asynctest.report.Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "THREADLOCALRANDOM MISUSE — clean";
            StringBuilder sb = new StringBuilder("THREADLOCALRANDOM MISUSE DETECTED (")
                    .append(IssueSeverity.MEDIUM.getLabel()).append("):\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Never store ThreadLocalRandom.current() in a field.\n")
              .append("    - Call ThreadLocalRandom.current() afresh on each thread, at each use site.\n");
            return sb.toString();
        }
    }
}
