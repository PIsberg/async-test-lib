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

    private static class BuilderState extends SelfGuard.TrackedInstance {
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
        /** Every thread that wrote, read or failed on the builder, per round, for the error finding. */
        final SelfGuard.RoundThreads roundUsers = new SelfGuard.RoundThreads();
        /**
         * The round with the most users among those an exception was recorded in. Two threads
         * that each failed alone in a different round never overlapped, so the error finding
         * counts one round's users, not the run's (#783).
         */
        final AtomicReference<SelfGuard.RoundThreads.@Nullable Round> errorRound = new AtomicReference<>();

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
        state.noteAccess(builder, false);
        state.roundUsers.add(Thread.currentThread());
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
        // Keep the busiest round an exception came from. Rounds run one after another, so an
        // earlier round's count is final; a tie goes to this round, which may still be growing.
        SelfGuard.RoundThreads.Round kept = state.errorRound.get();
        while (kept != round // NOPMD CompareObjectsWithEquals - one Round per round, by identity
                && (kept == null || round.size() >= kept.size())) {
            if (state.errorRound.compareAndSet(kept, round)) {
                break;
            }
            kept = state.errorRound.get();
        }
        state.errorCount.incrementAndGet();
    }

    private void recordMutation(StringBuilder builder, String name, String type) {
        if (builder == null) return;
        BuilderState state = resolve(builder, name);
        state.noteAccess(builder, true);
        state.mutatingThreads.add(Thread.currentThread().threadId());
        state.roundWriters.add(Thread.currentThread());
        state.roundUsers.add(Thread.currentThread());
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

            // The busiest round's writers: more than one writer has to be true of one round, the
            // same frame the sharing verdict is taken in, and that round's count is the one printed.
            // The two facts are each per round but not tied to the same round: a round where a
            // writer and a reader raced, plus a round with two guarded writers, still reports.
            int roundWriters = state.roundWriters.reportedSize();
            if (roundWriters > 1 && state.sawUnguardedSharing()) {
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
            int touchingThreads = errorRound == null ? 0 : errorRound.size();
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
