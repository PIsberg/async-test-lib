package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>A finding counts every flagged mutation but names at most {@value #MAX_REPORTED_MUTATIONS}
 * of them, and the detector keeps no more than it names plus the latest round's mutations still
 * waiting for that round's users, so a body that reconfigures a mapper on every execution does not
 * grow its memory with the run (#799).
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

    /**
     * How many flagged mutations a finding names; the count it prints covers all of them. A body
     * that reconfigures a mapper on every execution would otherwise keep one record per call for
     * the whole run (#799).
     */
    static final int MAX_REPORTED_MUTATIONS = 5;

    /** {@return the busier of two rounds by threads, the first on a tie; either may be {@code null}} */
    private static SelfGuard.RoundThreads.@Nullable Round busier(
            SelfGuard.RoundThreads.@Nullable Round a, SelfGuard.RoundThreads.@Nullable Round b) {
        return a == null || (b != null && b.size() > a.size()) ? b : a;
    }

    private static final class MutationRecord {
        final String description;
        final String threadName;

        MutationRecord(String description, Thread thread) {
            this.description = description;
            this.threadName = thread.getName();
        }

        static boolean usedByAnotherThread(SelfGuard.RoundThreads.Round users, long threadId) {
            return users.size() >= 2 || (users.size() == 1 && !users.contains(threadId));
        }
    }

    /**
     * The mutations one thread made in a round before another thread had used the mapper in it.
     * They share the thread and the round, so one verdict covers them all once the round's users
     * are complete; only the first few are kept to be named.
     */
    private static final class PendingMutations {
        final long threadId;
        final AtomicInteger count = new AtomicInteger();
        final List<MutationRecord> examples = new CopyOnWriteArrayList<>();

        PendingMutations(Thread thread) {
            this.threadId = thread.threadId();
        }

        void add(String description, Thread thread) {
            if (count.getAndIncrement() < MAX_REPORTED_MUTATIONS) {
                examples.add(new MutationRecord(description, thread));
            }
        }
    }

    /** One round's mutations of one mapper, judged once the round is over (#784, #799). */
    private static final class RoundMutations {
        /** The users of the round, complete once the next round has started. */
        final SelfGuard.RoundThreads.Round users;
        /** Pending mutations by mutating thread, by identity. */
        final Map<Thread, PendingMutations> pending = new ConcurrentHashMap<>();
        /** Whether a mutation was flagged in this round when it was recorded. */
        volatile boolean flagged;

        RoundMutations(SelfGuard.RoundThreads.Round users) {
            this.users = users;
        }

        PendingMutations pendingFor(Thread thread) {
            PendingMutations p = pending.get(thread);
            if (p == null) {
                p = pending.computeIfAbsent(thread, PendingMutations::new);
            }
            return p;
        }

        /** {@return whether a thread other than the one that made {@code p} used the mapper this round} */
        boolean raced(PendingMutations p) {
            return MutationRecord.usedByAnotherThread(users, p.threadId);
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
        /** Flagged mutations of the rounds judged so far; the first few are kept in {@link #examples}. */
        final AtomicInteger flaggedCount = new AtomicInteger();
        final List<MutationRecord> examples = new CopyOnWriteArrayList<>();
        /** The busiest round a flagged mutation of an already judged round was made in. */
        final AtomicReference<SelfGuard.RoundThreads.@Nullable Round> busiest = new AtomicReference<>();
        /**
         * The latest round with a mutation. A mutation made in a run's round before another thread
         * had used the mapper in it waits here until the round's users are complete: a use later in
         * the same round overlapped it as much as one before it would have (#784). The runner
         * finishes a round's workers before it starts the next, so the round is judged, and
         * dropped, when a mutation of a later round replaces it, or read in {@link #analyze()}.
         */
        final AtomicReference<@Nullable RoundMutations> open = new AtomicReference<>();

        State(String className) {
            this.className = className;
        }

        /** Counts a flagged mutation, keeping it as an example while fewer than the cap are kept. */
        void flag(String description, Thread thread) {
            if (flaggedCount.getAndIncrement() < MAX_REPORTED_MUTATIONS) {
                examples.add(new MutationRecord(description, thread));
            }
        }

        /**
         * {@return the mutations of {@code round}'s round}, first judging and dropping an earlier
         * round's. A thread still recording from an older round joins the newer one, as
         * {@link SelfGuard.RoundThreads} does.
         */
        RoundMutations mutationsOf(SelfGuard.RoundThreads.Round round) {
            RoundMutations current = open.get();
            while (current == null || current.users.number < round.number) {
                RoundMutations next = new RoundMutations(round);
                if (open.compareAndSet(current, next)) {
                    if (current != null) {
                        judge(current);
                    }
                    return next;
                }
                current = open.get();
            }
            return current;
        }

        private void judge(RoundMutations round) {
            boolean flagged = round.flagged;
            for (PendingMutations p : round.pending.values()) {
                if (!round.raced(p)) {
                    continue;
                }
                flagged = true;
                for (MutationRecord m : p.examples) {
                    if (flaggedCount.getAndIncrement() < MAX_REPORTED_MUTATIONS) {
                        examples.add(m);
                    }
                }
                flaggedCount.addAndGet(p.count.get() - p.examples.size());
            }
            if (flagged) {
                busiest.accumulateAndGet(round.users, SharedJsonMapperReconfigDetector::busier);
            }
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
            s.mutationsOf(users).flagged = true;
            s.flag(desc, thread);
            return;
        }
        // Nobody else has used it in this round yet. In a run's round somebody still may, and that
        // use overlaps this mutation too (#784); outside a run a later use cannot be told from one
        // that followed the configuration by a thread start, so it stays config-then-use.
        if (SelfGuard.Scope.current() != null) {
            s.mutationsOf(s.users.current()).pendingFor(thread).add(desc, thread);
        }
    }

    /**
     * {@return how many mutation records are kept across every mapper}: the examples a report
     * names and those of each mapper's latest round, however many mutations were recorded (#799)
     */
    int retainedMutationRecords() {
        int n = 0;
        for (State s : instances.values()) {
            n += s.examples.size();
            RoundMutations round = s.open.get();
            if (round != null) {
                for (PendingMutations p : round.pending.values()) {
                    n += p.examples.size();
                }
            }
        }
        return n;
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
            // The judged rounds plus the latest one, judged here without changing any state, so
            // analyze() stays idempotent.
            int flaggedCount = s.flaggedCount.get();
            List<MutationRecord> examples = new ArrayList<>(s.examples);
            // The users printed are one round's: the busiest round a flagged mutation was made in.
            SelfGuard.RoundThreads.@Nullable Round users = s.busiest.get();
            RoundMutations latest = s.open.get();
            if (latest != null) {
                boolean flagged = latest.flagged;
                for (PendingMutations p : latest.pending.values()) {
                    if (!latest.raced(p)) {
                        continue;
                    }
                    flagged = true;
                    flaggedCount += p.count.get();
                    for (MutationRecord m : p.examples) {
                        if (examples.size() < MAX_REPORTED_MUTATIONS) {
                            examples.add(m);
                        }
                    }
                }
                if (flagged) {
                    users = busier(users, latest.users);
                }
            }
            if (flaggedCount == 0 || users == null || !s.sawUnguardedSharing()) continue;
            List<String> descriptions = new ArrayList<>();
            List<String> mutatingThreads = new ArrayList<>();
            for (MutationRecord m : examples) {
                descriptions.add(m.description);
                if (!mutatingThreads.contains(m.threadName)) {
                    mutatingThreads.add(m.threadName);
                }
            }
            // Past the cap the count stays exact and only the naming stops, as with
            // SharedMemorySegmentRaceDetector's reported pairs.
            String named = String.join(", ", descriptions);
            if (flaggedCount > descriptions.size()) {
                named += String.format(", and %d more", flaggedCount - descriptions.size());
            }
            String msg = String.format(
                    "%s reconfigured during concurrent use: %s (mutated by %s) while "
                            + "used by %d thread(s) (%s) — configuration methods are not safe once a "
                            + "serializer/mapper is visible to other threads; an unsynchronized reconfiguration racing with "
                            + "serialize/deserialize calls causes intermittent corruption or "
                            + "ConcurrentModificationException in internal caches"
                            + SelfGuard.REPORT_NOTE + ".",
                    s.className,
                    named,
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
                            "mutationCount", flaggedCount,
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
