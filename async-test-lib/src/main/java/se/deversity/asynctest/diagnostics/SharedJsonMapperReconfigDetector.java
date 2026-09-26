package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects serializer/mapper instances (Jackson {@code ObjectMapper}, a Gson built via
 * {@code GsonBuilder}, or similar) that are reconfigured while another thread uses them in the same round.
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
 * directly. Mapper instances are tracked purely by reference identity
 * (compared with {@code ==}), and {@link Object#getClass()} is used only to label
 * findings in reports.
 *
 * <p>Configuring a mapper fully before it is shared ("config-then-use") is the correct,
 * safe pattern and is <em>not</em> flagged. This detector reports a mutation only when a
 * thread other than the mutating one uses the instance in the same invocation round - the
 * precondition for a configuration race. Inside a run that use may be recorded before or after
 * the mutation, since a round's workers are released together and either order overlaps (#784).
 * Outside a run the whole of it is one round and only a use recorded before the mutation counts,
 * because a later one cannot be told from a use that followed the configuration by a thread start.
 * Uses in other rounds never count: the runner finishes one round's workers before it starts the
 * next, so a use in another round cannot be in flight during the mutation, and with virtual
 * threads every round runs on fresh threads.
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
        final long threadId;
        /** The users of the round the mutation was made in. */
        final SelfGuard.RoundThreads.Round users;

        MutationRecord(String description, Thread thread, SelfGuard.RoundThreads.Round users) {
            this.description = description;
            this.threadName = thread.getName();
            this.threadId = thread.threadId();
            this.users = users;
        }

        /** {@return whether a thread other than the mutating one used the mapper in its round} */
        boolean usedByAnotherThread() {
            return usedByAnotherThread(users, threadId);
        }

        static boolean usedByAnotherThread(SelfGuard.RoundThreads.Round users, long threadId) {
            return users.size() >= 2 || (users.size() == 1 && !users.contains(threadId));
        }
    }

    private static final class State extends SelfGuard.TrackedInstance {
        final String className;
        /**
         * The using threads, per round. A use in an earlier round finished before this round
         * began, so neither "used by two threads" nor "a thread that never used it" may count
         * across a round boundary (#748).
         */
        final SelfGuard.RoundThreads users = new SelfGuard.RoundThreads();
        final List<MutationRecord> violatingMutations = new CopyOnWriteArrayList<>();
        /**
         * Mutations made in a run's round before another thread had used the mapper in it. Each is
         * judged in {@link #analyze()}, once its round's users are complete: a use later in the
         * same round overlapped it as much as one before it would have (#784).
         */
        final List<MutationRecord> pendingMutations = new CopyOnWriteArrayList<>();

        State(String className) {
            this.className = className;
        }
    }

    private final Map<IdentityKey, State> instances = new ConcurrentHashMap<>();

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
        s.users.add(Thread.currentThread());
    }

    /**
     * Record a configuration mutation (e.g. {@code configure}, {@code registerModule},
     * {@code setSerializationInclusion}) made against {@code mapper} on the current
     * thread.
     *
     * <p>A mutation is flagged when a thread other than the mutating one uses the instance in the
     * same invocation round. Inside a run that thread's use may come before or after the mutation:
     * the round's workers are released together, so either order overlaps. Outside a run, where
     * the whole of it is one round, only a use recorded before the mutation counts, so a
     * mutation observed before any {@link #recordUse} call for the same instance is the correct
     * "config-then-use" pattern and is never flagged there.
     *
     * @param mapper              the mapper/serializer instance under observation (null-safe)
     * @param mutationDescription descriptive label for reports (may be {@code null})
     */
    public void recordConfigMutation(Object mapper, String mutationDescription) {
        if (mapper == null) return;
        State s = stateFor(mapper);
        s.noteAccess(mapper);
        // Uses are this round's: the runner finished every earlier round's workers before this one
        // started, so nothing they did can be in flight now.
        SelfGuard.RoundThreads.Round users = s.users.inCurrentRound();
        Thread thread = Thread.currentThread();
        String desc = (mutationDescription != null) ? mutationDescription : "configuration change";
        if (users != null && MutationRecord.usedByAnotherThread(users, thread.threadId())) {
            s.violatingMutations.add(new MutationRecord(desc, thread, users));
            return;
        }
        // Nobody else has used it in this round yet. In a run's round somebody still may, and that
        // use overlaps this mutation too (#784); outside a run a later use cannot be told from one
        // that followed the configuration by a thread start, so it stays config-then-use.
        if (SelfGuard.Scope.current() != null) {
            s.pendingMutations.add(new MutationRecord(desc, thread, s.users.current()));
        }
    }

    private State stateFor(Object mapper) {
        IdentityKey key = new IdentityKey(mapper);
        State s = instances.get(key);
        if (s == null) {
            s = instances.computeIfAbsent(key, k -> new State(mapper.getClass().getName()));
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
            List<MutationRecord> flagged = new ArrayList<>(s.violatingMutations);
            for (MutationRecord m : s.pendingMutations) {
                if (m.usedByAnotherThread()) {
                    flagged.add(m);
                }
            }
            if (flagged.isEmpty() || !s.sawUnguardedSharing()) continue;
            List<String> descriptions = new ArrayList<>();
            List<String> mutatingThreads = new ArrayList<>();
            // The users printed are one round's: the busiest round a flagged mutation was made in.
            SelfGuard.RoundThreads.Round users = flagged.get(0).users;
            for (MutationRecord m : flagged) {
                descriptions.add(m.description);
                if (!mutatingThreads.contains(m.threadName)) {
                    mutatingThreads.add(m.threadName);
                }
                if (m.users.size() > users.size()) {
                    users = m.users;
                }
            }
            String msg = String.format(
                    "%s reconfigured during concurrent use: %s (mutated by %s) while "
                            + "used by %d thread(s) (%s) — configuration methods are not safe once a "
                            + "serializer/mapper is visible to other threads; an unsynchronized reconfiguration racing with "
                            + "serialize/deserialize calls causes intermittent corruption or "
                            + "ConcurrentModificationException in internal caches"
                            + SelfGuard.REPORT_NOTE + ".",
                    s.className,
                    String.join(", ", descriptions),
                    String.join(", ", mutatingThreads),
                    users.size(),
                    String.join(", ", users.names()));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedJsonMapperReconfig",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "className", s.className,
                            "mutationCount", flagged.size(),
                            "mutationDescriptions", List.copyOf(descriptions),
                            "usingThreadCount", users.size()),
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
