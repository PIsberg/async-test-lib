package se.deversity.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

/**
 * Detects {@link StringBuilder} instances shared across multiple threads without
 * synchronization.
 *
 * <p>{@code StringBuilder} is explicitly documented as <strong>not thread-safe</strong>.
 * When multiple threads call {@code append()}, {@code insert()}, {@code delete()}, or
 * {@code replace()} on the same instance concurrently, the internal character array can
 * be left in an inconsistent state, producing:
 * <ul>
 *   <li>Garbled / interleaved output strings</li>
 *   <li>{@code StringIndexOutOfBoundsException} from concurrent capacity changes</li>
 *   <li>Data loss (characters silently dropped)</li>
 * </ul>
 *
 * <p>Thread-safe alternatives:
 * <ul>
 *   <li>{@code StringBuffer} — synchronized on every operation (legacy, slower)</li>
 *   <li>{@code ThreadLocal<StringBuilder>} — one builder per thread, combine at the end</li>
 *   <li>Build strings locally per-thread and join with
 *       {@code String.join()} / {@code Collectors.joining()}</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectStringBuilderIssues = true)
 * void testStringBuilderSharing() {
 *     StringBuilder sb = new StringBuilder();
 *     AsyncTestContext.stringBuilderMonitor()
 *         .registerBuilder(sb, "shared-log-builder");
 *
 *     sb.append("entry");
 *     AsyncTestContext.stringBuilderMonitor()
 *         .recordAppend(sb, "shared-log-builder");
 * }
 * }</pre>
 */
public class StringBuilderDetector {

    /**
     * One round's sharing verdict for a builder. A fresh one per round, so the verdict is read for
     * the round it came from: the finding needs two writers and unguarded sharing in the same
     * round, and a verdict kept across the run stops tracking once any round has raced (#782).
     */
    private static final class RoundGuard extends SelfGuard.TrackedInstance {
        final int number;

        RoundGuard(int number) {
            this.number = number;
        }
    }

    private static class BuilderState {
        final String name;
        final AtomicInteger appendCount  = new AtomicInteger(0);
        final AtomicInteger insertCount  = new AtomicInteger(0);
        final AtomicInteger deleteCount  = new AtomicInteger(0);
        final AtomicInteger replaceCount = new AtomicInteger(0);
        final AtomicInteger readCount    = new AtomicInteger(0);
        final AtomicInteger errorCount   = new AtomicInteger(0);
        final Set<Long> mutatingThreads  = ConcurrentHashMap.newKeySet();
        /**
         * The writers per round, for the finding. {@link #mutatingThreads} spans the run and only
         * feeds the activity line: two writers in different rounds never overlapped (#748).
         */
        final SelfGuard.RoundThreads roundWriters = new SelfGuard.RoundThreads();
        /** The sharing verdict of the round in progress; {@code null} before the first access. */
        final AtomicReference<@Nullable RoundGuard> roundGuard = new AtomicReference<>();
        /**
         * The writers of the first round that had more than one writer and unguarded sharing; the
         * finding counts this round and no other. {@code null} while no round has met both.
         */
        final AtomicReference<SelfGuard.RoundThreads.@Nullable Round> sharedRound = new AtomicReference<>();
        /** Every thread that wrote, read or failed on the builder, per round, for the error finding. */
        final SelfGuard.RoundThreads roundUsers = new SelfGuard.RoundThreads();
        /**
         * The latest round an exception was recorded in, which may still be gaining users. Two
         * threads that each failed alone in a different round never overlapped, so the error
         * finding counts one round's users, not the run's (#783).
         */
        final AtomicReference<SelfGuard.RoundThreads.@Nullable Round> errorRound = new AtomicReference<>();
        /**
         * The busiest of the earlier rounds an exception was recorded in. Rounds run one after
         * another, so their counts are final; the finding counts the larger of this and
         * {@link #errorRound} at analysis, when both counts are final.
         */
        final AtomicReference<SelfGuard.RoundThreads.@Nullable Round> pastErrorRound = new AtomicReference<>();

        BuilderState(String name) {
            this.name = name;
        }
    }

    private final Map<IdentityKey, BuilderState> builders = new ConcurrentHashMap<>();

    /**
     * Register a {@code StringBuilder} for monitoring.
     *
     * @param builder the StringBuilder to monitor
     * @param name    a descriptive label for reports
     */
    public void registerBuilder(StringBuilder builder, String name) {
        if (builder == null) return;
        builders.putIfAbsent(new IdentityKey(builder),
                new BuilderState(name != null ? name : "StringBuilder@" + System.identityHashCode(builder)));
    }

    /**
     * Record an {@code append()} call.
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordAppend(StringBuilder builder, String name) {
        recordMutation(builder, name, "append");
    }

    /**
     * Record an {@code insert()} call.
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordInsert(StringBuilder builder, String name) {
        recordMutation(builder, name, "insert");
    }

    /**
     * Record a {@code delete()} or {@code deleteCharAt()} call.
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordDelete(StringBuilder builder, String name) {
        recordMutation(builder, name, "delete");
    }

    /**
     * Record a {@code replace()} call.
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordReplace(StringBuilder builder, String name) {
        recordMutation(builder, name, "replace");
    }

    /**
     * Record a read operation ({@code toString()}, {@code charAt()}, {@code length()}).
     *
     * @param builder the StringBuilder instance
     * @param name    the label (should match registration)
     */
    public void recordRead(StringBuilder builder, String name) {
        if (builder == null) return;
        BuilderState state = resolve(builder, name);
        RoundGuard guard = noteAccess(state, builder, false);
        state.roundUsers.add(Thread.currentThread());
        if (guard != null) {
            SelfGuard.RoundThreads.Round writers = state.roundWriters.inCurrentRound();
            if (writers != null) {
                checkShared(state, guard, writers);
            }
        }
        state.readCount.incrementAndGet();
    }

    /**
     * Record an exception caused by concurrent builder access.
     *
     * @param builder   the StringBuilder instance
     * @param name      the label
     * @param errorType a short description, e.g. "StringIndexOutOfBoundsException"
     */
    public void recordError(StringBuilder builder, String name, String errorType) {
        if (builder == null) return;
        BuilderState state = resolve(builder, name);
        // The thread that hit the error was using the builder, so it counts toward the sharing
        // the error finding requires (#501), within its round (#783). Not as a writer: an
        // exception says the call did not complete, so claiming a mutation would be claiming
        // more than was observed.
        SelfGuard.RoundThreads.Round round = state.roundUsers.add(Thread.currentThread());
        // Keep the latest round an exception came from, whose users may still be arriving, and
        // move the one it replaces, which is over and so final, into the busiest earlier one. A
        // round is compared only once its count is final, so one that gained users after its
        // exception is counted with them.
        while (true) {
            SelfGuard.RoundThreads.Round latest = state.errorRound.get();
            if (latest == round) { // NOPMD CompareObjectsWithEquals - one Round per round, by identity
                break;
            }
            if (latest != null && latest.number > round.number) {
                keepBusier(state.pastErrorRound, round);
                break;
            }
            if (state.errorRound.compareAndSet(latest, round)) {
                if (latest != null) {
                    keepBusier(state.pastErrorRound, latest);
                }
                break;
            }
        }
        state.errorCount.incrementAndGet();
    }

    private static void keepBusier(AtomicReference<SelfGuard.RoundThreads.@Nullable Round> kept,
                                   SelfGuard.RoundThreads.Round round) {
        SelfGuard.RoundThreads.Round current = kept.get();
        while (current == null || round.size() > current.size()) {
            if (kept.compareAndSet(current, round)) {
                return;
            }
            current = kept.get();
        }
    }

    /**
     * Probes the calling thread's locks into its round's verdict, which the first access of a
     * round starts afresh. Skipped, returning {@code null}, once a round has produced the finding:
     * nothing later can change it.
     */
    private static @Nullable RoundGuard noteAccess(BuilderState state, StringBuilder builder,
                                                   boolean forWrite) {
        if (state.sharedRound.get() != null) {
            return null;
        }
        int now = SelfGuard.RoundThreads.roundNow();
        RoundGuard guard = state.roundGuard.get();
        while (guard == null || guard.number < now) {
            RoundGuard next = new RoundGuard(now);
            if (state.roundGuard.compareAndSet(guard, next)) {
                guard = next;
                break;
            }
            guard = state.roundGuard.get();
        }
        guard.noteAccess(builder, forWrite);
        return guard;
    }

    /**
     * Latches the finding on {@code writers}' round when that round has more than one writer and
     * its verdict is unguarded sharing. Every access checks after recording its own part, the
     * verdict or the writer, so whichever access completes the pair sees both.
     */
    private static void checkShared(BuilderState state, RoundGuard guard,
                                    SelfGuard.RoundThreads.Round writers) {
        if (writers.number == guard.number && writers.size() > 1 && guard.sawUnguardedSharing()) {
            state.sharedRound.compareAndSet(null, writers);
        }
    }

    private void recordMutation(StringBuilder builder, String name, String type) {
        if (builder == null) return;
        BuilderState state = resolve(builder, name);
        RoundGuard guard = noteAccess(state, builder, true);
        state.mutatingThreads.add(Thread.currentThread().threadId());
        SelfGuard.RoundThreads.Round writers = state.roundWriters.add(Thread.currentThread());
        state.roundUsers.add(Thread.currentThread());
        if (guard != null) {
            checkShared(state, guard, writers);
        }
        switch (type) {
            case "append"  -> state.appendCount.incrementAndGet();
            case "insert"  -> state.insertCount.incrementAndGet();
            case "delete"  -> state.deleteCount.incrementAndGet();
            case "replace" -> state.replaceCount.incrementAndGet();
            default        -> { /* unrecognized mutation type — ignored */ }
        }
    }

    private BuilderState resolve(StringBuilder builder, String name) {
        IdentityKey key = new IdentityKey(builder);
        return builders.computeIfAbsent(key,
                k -> new BuilderState(name != null ? name : "StringBuilder@" + k.hashCode()));
    }

    /**
     * Analyse StringBuilder usage and return a report.
     *
     * @return the findings this detector collected during the run
     */
    public StringBuilderReport analyze() {
        StringBuilderReport report = new StringBuilderReport();

        for (BuilderState state : builders.values()) {
            int mutators = state.mutatingThreads.size();
            int writes   = state.appendCount.get() + state.insertCount.get()
                         + state.deleteCount.get() + state.replaceCount.get();
            int reads    = state.readCount.get();
            int errors   = state.errorCount.get();

            if (writes == 0 && reads == 0) continue;

            report.totalBuilders++;

            // More than one writer and unguarded sharing, both in one round, and that round's
            // writers are the count printed. A round where a writer and a reader raced, plus a
            // round with two guarded writers, is two rounds that each met one condition (#782).
            SelfGuard.RoundThreads.Round shared = state.sharedRound.get();
            if (shared != null) {
                int roundWriters = shared.size();
                report.sharedBuilderViolations.add(String.format(
                        "%s: mutated by %d threads (append: %d, insert: %d, delete: %d, replace: %d) — NOT THREAD SAFE!" + SelfGuard.REPORT_NOTE,
                        state.name, roundWriters,
                        state.appendCount.get(), state.insertCount.get(),
                        state.deleteCount.get(), state.replaceCount.get()));
            }

            // Concurrent access is part of the claim, so it has to be part of the evidence.
            // One thread appending to its own StringBuilder and catching an exception has hit a
            // bug in its own indexing; calling that "from concurrent access" attributes it to a
            // race that did not happen. The count still shows up under activity below (#501).
            // The users are counted in the busiest round an exception came from: one thread per
            // round, each failing alone, is the same single-thread shape repeated (#783).
            SelfGuard.RoundThreads.Round errorRound = state.errorRound.get();
            SelfGuard.RoundThreads.Round pastErrorRound = state.pastErrorRound.get();
            int touchingThreads = Math.max(errorRound == null ? 0 : errorRound.size(),
                    pastErrorRound == null ? 0 : pastErrorRound.size());
            if (errors > 0 && touchingThreads > 1) {
                report.builderErrors.add(String.format(
                        "%s: %d exception(s) while %d threads used it (possible "
                            + "StringIndexOutOfBoundsException from concurrent access)",
                        state.name, errors, touchingThreads));
            }

            report.builderActivity.add(String.format(
                    "%s: writes: %d from %d thread(s), reads: %d, errors: %d",
                    state.name, writes, mutators, reads, errors));
        }

        return report;
    }

    // ---- Report ----------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class StringBuilderReport {

        int totalBuilders = 0;
        final java.util.List<String> sharedBuilderViolations = new java.util.ArrayList<>();
        final java.util.List<String> builderErrors           = new java.util.ArrayList<>();
        /**
         * One line per builder object, named but not keyed by the name: two builders may share
         * a name, and filed under it the second one's line overwrote the first's (#789).
         */
        final java.util.List<String> builderActivity         = new java.util.ArrayList<>();

        /**
         * Returns {@code true} when shared-mutation or errors were detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
        public boolean hasIssues() {
            return !sharedBuilderViolations.isEmpty() || !builderErrors.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("STRING BUILDER ISSUES DETECTED:\n");

            if (!sharedBuilderViolations.isEmpty()) {
                sb.append("  Shared StringBuilder Mutations (NOT THREAD SAFE):\n");
                for (String v : sharedBuilderViolations) {
                    sb.append("    - ").append(v).append("\n");
                }
            }

            if (!builderErrors.isEmpty()) {
                sb.append("  Concurrent Access Errors:\n");
                for (String e : builderErrors) {
                    sb.append("    - ").append(e).append("\n");
                }
            }

            if (!builderActivity.isEmpty()) {
                sb.append("  Builder Activity:\n");
                for (String activity : builderActivity) {
                    sb.append("    - ").append(activity).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("""
  Why: StringBuilder is explicitly not thread-safe. The internal char[] and count field are updated
       without synchronization. Concurrent append() calls corrupt the buffer, producing garbled output,
       StringIndexOutOfBoundsException, or silently lost characters — bugs that vary by thread scheduling.
  Fix:
    - Build strings locally per thread and combine the results afterwards (collect into a list, then join)
    - Use ThreadLocal<StringBuilder> if you must reuse a buffer per thread (call sb.setLength(0) to reset)
    - Use StringBuffer instead if sharing across threads is unavoidable (synchronized, but slower)\
""");
            return sb.toString();
        }
    }
}
