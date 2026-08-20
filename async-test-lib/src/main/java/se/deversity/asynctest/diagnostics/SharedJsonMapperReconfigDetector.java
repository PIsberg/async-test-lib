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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects serializer/mapper instances (Jackson {@code ObjectMapper}, a Gson built via
 * {@code GsonBuilder}, or similar) that are reconfigured after concurrent use has begun.
 *
 * <p><strong>Why it matters.</strong> Serialization mappers are typically documented as
 * thread-safe for read/write operations ({@code readValue}/{@code writeValue}) once fully
 * configured, but their configuration methods ({@code configure}, {@code registerModule},
 * {@code setSerializationInclusion}, or Gson's builder-style setters) are <em>not</em>
 * safe to call once the instance is visible to other threads. A configuration mutation
 * racing with an in-flight (de)serialization call can corrupt output intermittently or
 * throw a {@code ConcurrentModificationException} out of an internal cache (e.g.
 * Jackson's per-type serializer/deserializer cache).
 *
 * <p>This library is dependency-free: it never references Jackson or Gson types
 * directly. Mapper instances are tracked purely by identity
 * ({@link System#identityHashCode}), and {@link Object#getClass()} is used only to label
 * findings in reports.
 *
 * <p>Configuring a mapper fully before it is shared ("config-then-use") is the correct,
 * safe pattern and is <em>not</em> flagged. This detector only reports a mutation that
 * happens after the instance has already been used, and only when that mutation either
 * comes from a thread that never used the instance, or the instance has already been used
 * from two or more distinct threads — the exact preconditions for a configuration race.
 *
 * <p>Synchronization awareness is partial. A use or a mutation recorded while the accessing
 * thread holds the mapper's own monitor - the {@code synchronized (mapper)} idiom - counts as
 * guarded, and a mapper whose every recorded call was guarded produces no finding. A guard on
 * any other lock object is invisible and still fires; treat such a finding as a prompt to
 * verify the synchronization, or to freeze configuration before sharing.
 *
 * <p>The safe pattern is to freeze configuration before publishing the mapper to other
 * threads, and to obtain per-call variation via {@code ObjectMapper.copy()},
 * {@code ObjectReader}/{@code ObjectWriter}, or by building a fresh {@code Gson} per
 * desired configuration rather than mutating a shared instance.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new SharedJsonMapperReconfigDetector();
 * // inside serialization/deserialization call sites:
 * d.recordUse(objectMapper);
 * // inside configuration call sites:
 * d.recordConfigMutation(objectMapper, "registerModule(JavaTimeModule)");
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; using-thread sets are ConcurrentHashMap.newKeySet(); violating mutations recorded in a CopyOnWriteArrayList.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedJsonMapperReconfigDetectorTest.java"
)
public final class SharedJsonMapperReconfigDetector {

    private static final class MutationRecord {
        final String description;
        final String threadName;

        MutationRecord(String description, String threadName) {
            this.description = description;
            this.threadName = threadName;
        }
    }

    private static final class State extends SelfGuard.TrackedInstance {
        final String className;
        final Set<Long>   usingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> usingThreadNames = ConcurrentHashMap.newKeySet();
        final List<MutationRecord> violatingMutations = new CopyOnWriteArrayList<>();

        State(String className) {
            this.className = className;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record a serialization or deserialization call made against {@code mapper} on the
     * current thread.
     *
     * @param mapper the mapper/serializer instance under observation (null-safe)
     */
    public void recordUse(Object mapper) {
        if (mapper == null) return;
        State s = stateFor(mapper);
        s.noteAccess(mapper);
        Thread thread = Thread.currentThread();
        s.usingThreadIds.add(thread.threadId());
        s.usingThreadNames.add(thread.getName());
    }

    /**
     * Record a configuration mutation (e.g. {@code configure}, {@code registerModule},
     * {@code setSerializationInclusion}) made against {@code mapper} on the current
     * thread.
     *
     * <p>A mutation observed before any {@link #recordUse} call for the same instance is
     * the correct "config-then-use" pattern and is never flagged. A mutation observed
     * after use has begun is only flagged when it either originates from a thread that
     * never used the instance, or the instance has already been used from two or more
     * distinct threads.
     *
     * @param mapper              the mapper/serializer instance under observation (null-safe)
     * @param mutationDescription descriptive label for reports (may be {@code null})
     */
    public void recordConfigMutation(Object mapper, String mutationDescription) {
        if (mapper == null) return;
        State s = stateFor(mapper);
        s.noteAccess(mapper);
        if (s.usingThreadIds.isEmpty()) {
            return;
        }
        Thread thread = Thread.currentThread();
        boolean usedByMultipleThreads = s.usingThreadIds.size() >= 2;
        boolean fromNonUsingThread = !s.usingThreadIds.contains(thread.threadId());
        if (usedByMultipleThreads || fromNonUsingThread) {
            String desc = (mutationDescription != null) ? mutationDescription : "configuration change";
            s.violatingMutations.add(new MutationRecord(desc, thread.getName()));
        }
    }

    private State stateFor(Object mapper) {
        int id = System.identityHashCode(mapper);
        State s = instances.get(id);
        if (s == null) {
            s = instances.computeIfAbsent(id, k -> new State(mapper.getClass().getName()));
        }
        return s;
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.violatingMutations.isEmpty() || !s.sawUnguardedAccess()) continue;
            List<String> descriptions = new ArrayList<>();
            List<String> mutatingThreads = new ArrayList<>();
            for (MutationRecord m : s.violatingMutations) {
                descriptions.add(m.description);
                if (!mutatingThreads.contains(m.threadName)) {
                    mutatingThreads.add(m.threadName);
                }
            }
            String msg = String.format(
                    "%s reconfigured after concurrent use had begun: %s (mutated by %s) while "
                            + "used by %d thread(s) (%s) — configuration methods are not safe once a "
                            + "serializer/mapper is visible to other threads; an unsynchronized reconfiguration racing with "
                            + "serialize/deserialize calls causes intermittent corruption or "
                            + "ConcurrentModificationException in internal caches"
                            + SelfGuard.REPORT_NOTE + ".",
                    s.className,
                    String.join(", ", descriptions),
                    String.join(", ", mutatingThreads),
                    s.usingThreadIds.size(),
                    String.join(", ", s.usingThreadNames));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedJsonMapperReconfig",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "className", s.className,
                            "mutationCount", s.violatingMutations.size(),
                            "mutationDescriptions", List.copyOf(descriptions),
                            "usingThreadCount", s.usingThreadIds.size()),
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
            if (violations.isEmpty()) return "SHARED JSON MAPPER RECONFIG — clean";
            StringBuilder sb = new StringBuilder("SHARED JSON MAPPER RECONFIG DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Freeze mapper/builder configuration before publishing it to other threads.\n")
              .append("    - Use ObjectMapper.copy() or a per-call ObjectReader/ObjectWriter for variation.\n")
              .append("    - For Gson, build a new instance per desired configuration instead of mutating a shared GsonBuilder result.\n");
            return sb.toString();
        }
    }
}
