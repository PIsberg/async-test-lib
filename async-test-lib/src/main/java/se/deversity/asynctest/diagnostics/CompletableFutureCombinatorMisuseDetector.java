package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects code that moves on before a {@link CompletableFuture} combinator has actually finished,
 * and failures that a combinator makes unreachable.
 *
 * <p>{@link CompletableFuture#allOf} and {@link CompletableFuture#anyOf} do not wait for
 * anything. They return a <em>new</em> future, and that future is the only thing that knows when
 * the group is done. Drop it, or read it with {@link CompletableFuture#getNow} instead of
 * {@code join()}, and the code carries on while the constituents are still running - the shape
 * behind "the test passed but the rows were not written yet". {@code anyOf} adds a second trap:
 * once one constituent wins, a failure in any of the others has nowhere to go.
 *
 * <p>Every finding is anchored to an observation, never to the mere use of a combinator:
 * <ul>
 *   <li>{@link IssueSeverity#HIGH} - the combined future was never awaited and constituents
 *       were <em>still incomplete</em> when the run ended. A combinator whose constituents all
 *       finished produces no finding, even if nobody awaited it.</li>
 *   <li>{@link IssueSeverity#HIGH} - the combined future was read with a non-blocking probe
 *       ({@code getNow}, {@code isDone}) at a point when fewer constituents had completed than
 *       the combinator was given. The detector compares recorded event order, so this is what
 *       happened, not what might.</li>
 *   <li>{@link IssueSeverity#MEDIUM} - an {@code anyOf} constituent completed exceptionally
 *       after the combined future was already read. That exception reaches no handler.</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.cfCombinatorMisuseDetector();
 *
 * CompletableFuture<Void> all = CompletableFuture.allOf(a, b);
 * d.recordCombinator(all, "writes", "allOf", 2, Thread.currentThread());
 * a.whenComplete((v, ex) -> d.recordConstituentCompleted(all, "a", ex != null, Thread.currentThread()));
 * b.whenComplete((v, ex) -> d.recordConstituentCompleted(all, "b", ex != null, Thread.currentThread()));
 *
 * all.join();
 * d.recordAwait(all, "join", Thread.currentThread());   // both constituents already in: silent
 * }</pre>
 *
 * <p>Record constituents through {@code whenComplete}, not {@code thenRun}: {@code thenRun} is
 * skipped when the constituent fails, and a failed constituent that was never recorded is
 * indistinguishable from one still running - the group would be reported as unfinished when it
 * finished with a failure. Attach the recording callbacks after building the combinator, as
 * above: dependents fire last-registered first, so the record is sequenced before the combined
 * future completes and a read that follows is compared against the right count.
 *
 * @since 1.10.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "ConcurrentHashMap keyed on the combined future's identity; constituent and await events "
             + "are copy-on-write lists carrying atomically issued sequence numbers, so the "
             + "before/after comparison never depends on a wall clock.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCombinatorMisuseDetectorTest.java"
)
public final class CompletableFutureCombinatorMisuseDetector {

    /** Await styles that return immediately instead of waiting for the group. */
    private static final Set<String> NON_BLOCKING_READS = Set.of("getNow", "isDone", "poll", "complete");

    /**
     * Case-insensitive equality without case folding.
     *
     * <p>{@code toLowerCase} would read better, but callers pass these labels as free text and
     * the comparison is done with {@link String#CASE_INSENSITIVE_ORDER} so no locale-dependent
     * mapping is applied to user input.
     */
    private static boolean sameIgnoringCase(String a, String b) {
        return String.CASE_INSENSITIVE_ORDER.compare(a, b) == 0;
    }

    /** {@return whether {@code how} names a read that does not wait for the group} */
    private static boolean isNonBlockingRead(String how) {
        for (String candidate : NON_BLOCKING_READS) {
            if (sameIgnoringCase(candidate, how)) return true;
        }
        return false;
    }

    private static final class ConstituentEvent {
        final long    seq;
        final String  label;
        final boolean exceptional;

        ConstituentEvent(long seq, String label, boolean exceptional) {
            this.seq         = seq;
            this.label       = label;
            this.exceptional = exceptional;
        }
    }

    private static final class AwaitEvent {
        final long   seq;
        final String how;
        final String threadName;

        AwaitEvent(long seq, String how, String threadName) {
            this.seq        = seq;
            this.how        = how;
            this.threadName = threadName;
        }
    }

    private static final class CombinatorState {
        final String                 label;
        final String                 kind;
        final int                    arity;
        final String                 creatingThread;
        final List<ConstituentEvent> constituents = new CopyOnWriteArrayList<>();
        final List<AwaitEvent>       awaits       = new CopyOnWriteArrayList<>();

        CombinatorState(String label, String kind, int arity, String creatingThread) {
            this.label          = label;
            this.kind           = kind;
            this.arity          = arity;
            this.creatingThread = creatingThread;
        }
    }

    private final Map<Integer, CombinatorState> combinators = new ConcurrentHashMap<>();
    private final AtomicLong                    sequence    = new AtomicLong();
    private volatile boolean                    enabled     = true;

    /**
     * Record the creation of a combinator future.
     *
     * @param combined the future returned by {@code allOf}/{@code anyOf}/{@code thenCombine},
     *                 tracked by identity
     * @param label    a label identifying the combinator in the report
     * @param kind     which combinator this is, e.g. {@code "allOf"} or {@code "anyOf"}
     * @param arity    how many constituent futures were handed to it
     * @param thread   the creating thread
     */
    public void recordCombinator(CompletableFuture<?> combined, String label,
                                 String kind, int arity, Thread thread) {
        if (!enabled || combined == null || thread == null) return;
        int id = System.identityHashCode(combined);
        String name = label != null ? label : "combinator@" + id;
        combinators.computeIfAbsent(id, k -> new CombinatorState(
                name, kind != null ? kind : "allOf", Math.max(arity, 0), thread.getName()));
    }

    /**
     * Record that one of the constituent futures has completed.
     *
     * @param combined         the combinator future the constituent belongs to
     * @param constituentLabel a label identifying the constituent in the report
     * @param exceptional      {@code true} if it completed with a failure
     * @param thread           the completing thread
     */
    public void recordConstituentCompleted(CompletableFuture<?> combined, String constituentLabel,
                                           boolean exceptional, Thread thread) {
        if (!enabled || combined == null || thread == null) return;
        CombinatorState s = combinators.get(System.identityHashCode(combined));
        if (s == null) return;   // combinator was never registered; nothing to say about it
        s.constituents.add(new ConstituentEvent(
                sequence.incrementAndGet(),
                constituentLabel != null ? constituentLabel : "constituent",
                exceptional));
    }

    /**
     * Record that the combinator future's result was read.
     *
     * @param combined the combinator future
     * @param how      how it was read: {@code "join"}, {@code "get"}, {@code "getNow"},
     *                 {@code "isDone"}. The non-blocking styles are what produce a finding.
     * @param thread   the reading thread
     */
    public void recordAwait(CompletableFuture<?> combined, String how, Thread thread) {
        if (!enabled || combined == null || thread == null) return;
        CombinatorState s = combinators.get(System.identityHashCode(combined));
        if (s == null) return;
        s.awaits.add(new AwaitEvent(
                sequence.incrementAndGet(), how != null ? how : "join", thread.getName()));
    }

    /** Turn recording off; already-recorded events are kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded combinator events and builds the report.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (CombinatorState s : combinators.values()) {
            List<ConstituentEvent> done = new ArrayList<>(s.constituents);
            List<AwaitEvent> awaits = new ArrayList<>(s.awaits);

            if (awaits.isEmpty()) {
                // Only a fact if constituents were genuinely still outstanding at the end of the
                // run. An unawaited combinator whose constituents all finished lost nothing.
                if (s.arity > 0 && done.size() < s.arity) {
                    String msg = String.format(
                            "%s combinator '%s' (created on '%s' over %d futures) was never awaited, and only "
                            + "%d constituent(s) had completed when the run ended. The future returned by %s is "
                            + "the only thing that waits - dropping it lets the caller continue while %d "
                            + "future(s) are still running.",
                            s.kind, s.label, s.creatingThread, s.arity, done.size(), s.kind,
                            s.arity - done.size());
                    r.violations.add(msg);
                    r.structuredViolations.add(new Violation(
                            "CompletableFutureCombinatorMisuse",
                            IssueSeverity.HIGH, msg, List.of(),
                            Map.of("label", s.label, "kind", s.kind, "arity", s.arity,
                                   "completed", done.size(), "awaited", false),
                            Instant.now()));
                }
                continue;
            }

            for (AwaitEvent a : awaits) {
                if (!isNonBlockingRead(a.how)) continue;
                int completedBefore = 0;
                for (ConstituentEvent c : done) if (c.seq < a.seq) completedBefore++;
                if (s.arity > 0 && completedBefore < s.arity) {
                    String msg = String.format(
                            "%s combinator '%s' was read with %s() on thread '%s' when only %d of %d "
                            + "constituent(s) had completed. A non-blocking read does not wait; the caller "
                            + "proceeded on a group that was still running.",
                            s.kind, s.label, a.how, a.threadName, completedBefore, s.arity);
                    r.violations.add(msg);
                    r.structuredViolations.add(new Violation(
                            "CompletableFutureCombinatorMisuse",
                            IssueSeverity.HIGH, msg, List.of(),
                            Map.of("label", s.label, "kind", s.kind, "arity", s.arity,
                                   "completedAtRead", completedBefore, "readWith", a.how,
                                   "readBy", a.threadName),
                            Instant.now()));
                }
            }

            if (sameIgnoringCase("anyOf", s.kind)) {
                long lastAwaitSeq = 0;
                for (AwaitEvent a : awaits) if (a.seq > lastAwaitSeq) lastAwaitSeq = a.seq;
                Set<String> orphaned = new LinkedHashSet<>();
                for (ConstituentEvent c : done) {
                    if (c.exceptional && c.seq > lastAwaitSeq) orphaned.add(c.label);
                }
                if (!orphaned.isEmpty()) {
                    String msg = String.format(
                            "anyOf combinator '%s': constituent(s) [%s] failed after the combined future had "
                            + "already been read. anyOf propagates only the first completion, so these failures "
                            + "reach no handler and no report.",
                            s.label, String.join(", ", orphaned));
                    r.violations.add(msg);
                    r.structuredViolations.add(new Violation(
                            "CompletableFutureCombinatorMisuse",
                            IssueSeverity.MEDIUM, msg, List.of(),
                            Map.of("label", s.label, "kind", s.kind,
                                   "orphanedFailures", String.join(",", orphaned)),
                            Instant.now()));
                }
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "COMPLETABLE FUTURE COMBINATOR MISUSE - clean";
            StringBuilder sb = new StringBuilder("COMPLETABLE FUTURE COMBINATOR MISUSE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: allOf() and anyOf() are constructors, not barriers. They return a future and return\n")
              .append("       immediately; nothing has been waited for until something joins that future. Reading\n")
              .append("       it with getNow() or isDone() waits for nothing at all, and anyOf() drops every\n")
              .append("       failure that is not the first completion.\n")
              .append("  Fix:\n")
              .append("    - Join the combinator, not the constituents: allOf(a, b, c).join() before proceeding\n")
              .append("    - Keep the result: allOf(...).thenApply(v -> ...) so downstream work is chained to the\n")
              .append("      group rather than racing it\n")
              .append("    - With anyOf, attach a whenComplete() to each constituent so a loser's failure is still\n")
              .append("      logged instead of vanishing\n");
            return sb.toString();
        }
    }
}
