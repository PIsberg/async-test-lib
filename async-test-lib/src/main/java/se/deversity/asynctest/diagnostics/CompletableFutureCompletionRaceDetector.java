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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects two or more threads racing to complete the same {@link CompletableFuture}, where the
 * losers' values or exceptions are discarded without a trace.
 *
 * <p>{@link CompletableFuture#complete(Object)} and
 * {@link CompletableFuture#completeExceptionally(Throwable)} return {@code false} when the future
 * was already completed. That return value is the only signal that a result was thrown away, and
 * almost no production code reads it. When N threads race to publish into one future - the usual
 * shape of a callback bridge, a cache-fill, or a "first one to answer wins" fan-out - every loser
 * silently drops its result, and a failure that a loser tried to publish disappears entirely.
 *
 * <p>The finding here is a fact rather than an inference: the detector reports only completion
 * attempts that were <em>observed to lose</em>. A future completed by exactly one thread produces
 * no finding, so a correctly guarded pipeline stays silent. A single recorded attempt that lost
 * to a completion the detector never saw - an {@code orTimeout} firing first, a raw
 * {@code complete()} elsewhere - is reported all the same: the value it carried is gone whether
 * or not the winner was instrumented, and the report says the winner was not observed.
 *
 * <p>Severity follows what was actually lost:
 * <ul>
 *   <li>{@link IssueSeverity#HIGH} when a losing attempt carried an exception, or a value that
 *       differs from the one that won - a different outcome was discarded.</li>
 *   <li>{@link IssueSeverity#MEDIUM} when every losing attempt carried a value equal to the
 *       winner's - still a race, but this run happened to lose nothing observable.</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest} - record the outcome of each attempt:
 * <pre>{@code
 * var d = AsyncTestContext.cfCompletionRaceDetector();
 * boolean won = result.complete(myValue);
 * d.recordCompletionAttempt(result, "lookup", myValue, won, Thread.currentThread());
 * }</pre>
 *
 * <p>or let the detector make the call and read the outcome itself, which removes the chance of
 * passing the wrong {@code won} flag:
 * <pre>{@code
 * AsyncTestContext.cfCompletionRaceDetector().complete(result, "lookup", myValue);
 * }</pre>
 *
 * @since 1.10.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "ConcurrentHashMap keyed on future identity; per-future attempt list is copy-on-write "
             + "and the sequence counter is atomic, so concurrent recorders never lose an attempt.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCompletionRaceDetectorTest.java"
)
public final class CompletableFutureCompletionRaceDetector {

    /** One observed completion attempt on one future. */
    private static final class Attempt {
        final long    seq;
        final String  threadName;
        final boolean won;
        final boolean exceptional;
        final String  rendered;

        Attempt(long seq, String threadName, boolean won, boolean exceptional, String rendered) {
            this.seq         = seq;
            this.threadName  = threadName;
            this.won         = won;
            this.exceptional = exceptional;
            this.rendered    = rendered;
        }
    }

    private static final class FutureState {
        final String        label;
        final List<Attempt> attempts = new CopyOnWriteArrayList<>();

        FutureState(String label) { this.label = label; }
    }

    private final Map<Integer, FutureState> futures  = new ConcurrentHashMap<>();
    private final AtomicInteger             sequence = new AtomicInteger();
    private volatile boolean                enabled  = true;

    /**
     * Complete {@code future} with {@code value} and record whether this thread won the race.
     *
     * <p>Preferred over {@link #recordCompletionAttempt} because the detector reads
     * {@link CompletableFuture#complete(Object)}'s return value itself.
     *
     * @param <T>    the future's value type
     * @param future the future to complete, tracked by identity
     * @param label  a label identifying the future in the report
     * @param value  the value to publish
     * @return {@code true} if this call completed the future, {@code false} if it lost the race
     */
    public <T> boolean complete(CompletableFuture<T> future, String label, T value) {
        if (future == null) return false;
        boolean won = future.complete(value);
        recordCompletionAttempt(future, label, value, won, Thread.currentThread());
        return won;
    }

    /**
     * Complete {@code future} exceptionally and record whether this thread won the race.
     *
     * @param future    the future to complete, tracked by identity
     * @param label     a label identifying the future in the report
     * @param throwable the failure to publish
     * @return {@code true} if this call completed the future, {@code false} if it lost the race
     */
    public boolean completeExceptionally(CompletableFuture<?> future, String label, Throwable throwable) {
        if (future == null) return false;
        boolean won = future.completeExceptionally(throwable);
        recordExceptionalCompletionAttempt(future, label, throwable, won, Thread.currentThread());
        return won;
    }

    /**
     * Record the outcome of a {@link CompletableFuture#complete(Object)} call made by the caller.
     *
     * @param future the future that was completed, tracked by identity
     * @param label  a label identifying the future in the report
     * @param value  the value this thread tried to publish (may be {@code null})
     * @param won    what {@code complete()} returned - {@code false} means the value was dropped
     * @param thread the thread that made the attempt
     */
    public void recordCompletionAttempt(CompletableFuture<?> future, String label,
                                        Object value, boolean won, Thread thread) {
        record(future, label, won, false, render(value), thread);
    }

    /**
     * Record the outcome of a {@link CompletableFuture#completeExceptionally(Throwable)} call.
     *
     * @param future    the future that was completed, tracked by identity
     * @param label     a label identifying the future in the report
     * @param throwable the failure this thread tried to publish (may be {@code null})
     * @param won       what {@code completeExceptionally()} returned
     * @param thread    the thread that made the attempt
     */
    public void recordExceptionalCompletionAttempt(CompletableFuture<?> future, String label,
                                                   Throwable throwable, boolean won, Thread thread) {
        String rendered = throwable == null
                ? "null"
                : throwable.getClass().getSimpleName()
                  + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
        record(future, label, won, true, rendered, thread);
    }

    private void record(CompletableFuture<?> future, String label, boolean won,
                        boolean exceptional, String rendered, Thread thread) {
        if (!enabled || future == null || thread == null) return;
        int id = System.identityHashCode(future);
        String name = label != null ? label : "CompletableFuture@" + id;
        FutureState state = futures.computeIfAbsent(id, k -> new FutureState(name));
        state.attempts.add(new Attempt(
                sequence.incrementAndGet(), thread.getName(), won, exceptional, rendered));
    }

    private static String render(Object value) {
        if (value == null) return "null";
        try {
            return String.valueOf(value);
        } catch (RuntimeException e) {
            // A user toString() that throws must not take the detector down with it.
            return value.getClass().getSimpleName() + "@" + System.identityHashCode(value);
        }
    }

    /** Turn recording off; already-recorded attempts are kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded completion attempts and builds the report.
     *
     * <p>Every future with at least one recorded attempt that lost is reported. A lone loser is
     * a discarded value like any other; it only means the winning completion happened where the
     * detector could not see it, which the report says.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (FutureState s : futures.values()) {
            List<Attempt> all = new ArrayList<>(s.attempts);
            if (all.isEmpty()) continue;

            List<Attempt> losers = new ArrayList<>();
            Attempt winner = null;
            for (Attempt a : all) {
                if (a.won) {
                    if (winner == null || a.seq < winner.seq) winner = a;
                } else {
                    losers.add(a);
                }
            }
            if (losers.isEmpty()) continue;

            String winningValue = winner == null ? "(not observed)" : winner.rendered;

            boolean lostAnException = false;
            boolean lostADifferentValue = false;
            for (Attempt a : losers) {
                if (a.exceptional) lostAnException = true;
                else if (winner != null && !a.rendered.equals(winner.rendered)) lostADifferentValue = true;
            }
            // A winner that was never recorded means the future was completed somewhere the
            // detector cannot see, so we cannot claim the dropped values were equal to it.
            boolean unknownWinner = winner == null;

            IssueSeverity severity = (lostAnException || lostADifferentValue || unknownWinner)
                    ? IssueSeverity.HIGH : IssueSeverity.MEDIUM;

            Set<String> threads = new LinkedHashSet<>();
            for (Attempt a : all) threads.add(a.threadName);

            StringBuilder dropped = new StringBuilder();
            for (Attempt a : losers) {
                if (dropped.length() > 0) dropped.append("; ");
                dropped.append('\'').append(a.threadName).append("' dropped ")
                       .append(a.exceptional ? "exception " : "value ").append(a.rendered);
            }

            String msg = String.format(
                    "CompletableFuture '%s' took %d recorded completion attempt(s) across %d thread(s) (%s); "
                    + "%d attempt(s) lost the race and were discarded - %s. The winning completion was %s. "
                    + "complete()/completeExceptionally() returns false for the loser, and nothing read it.",
                    s.label, all.size(), threads.size(), String.join(", ", threads),
                    losers.size(), dropped, winningValue);

            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "CompletableFutureCompletionRace",
                    severity,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "attempts", all.size(),
                            "lostAttempts", losers.size(),
                            "threads", String.join(",", threads),
                            "lostAnException", lostAnException,
                            "lostADifferentValue", lostADifferentValue,
                            "winningValue", winningValue
                    ),
                    Instant.now()));
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
            if (violations.isEmpty()) return "COMPLETABLE FUTURE COMPLETION RACE - clean";
            StringBuilder sb = new StringBuilder("COMPLETABLE FUTURE COMPLETION RACE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: complete() and completeExceptionally() are first-writer-wins. Every later attempt\n")
              .append("       returns false and its value - or its exception - is discarded. When several threads\n")
              .append("       can publish into the same future, a failure raised by a loser vanishes and the caller\n")
              .append("       sees a success.\n")
              .append("  Fix:\n")
              .append("    - Give each producer its own future and combine them, instead of sharing one slot\n")
              .append("    - If a race is intended ('first answer wins'), read the boolean and route the losers\n")
              .append("      somewhere: log them, or fold them into the winner's result\n")
              .append("    - Never repair a lost completion with obtrudeValue() - that replaces an already-published\n")
              .append("      result and races with every downstream stage\n");
            return sb.toString();
        }
    }
}
