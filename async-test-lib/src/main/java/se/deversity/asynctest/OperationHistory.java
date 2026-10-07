package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.LinearizabilityChecker.Op;
import se.deversity.asynctest.LinearizabilityChecker.Verdict;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.WorkerSlot;
import se.deversity.asynctest.report.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Records the operations an {@code @AsyncTest} body performs on a concurrent object and checks that
 * every round's results are linearizable: explainable by some order of the operations, one at a
 * time, that respects real time (#924).
 *
 * <p>The detectors recognise known race shapes. This check needs no shape: a counter whose increment
 * is a read followed by a write fails it the first time two overlapping increments return the same
 * value, whether or not any detector knows the pattern.
 *
 * <pre>{@code
 * private static final OperationHistory<AtomicInteger> HISTORY = OperationHistory.of(AtomicInteger::new);
 *
 * @AsyncTest(threads = 3, invocations = 100)
 * void increments() {
 *     AtomicInteger counter = HISTORY.subject();          // one fresh counter per round
 *     HISTORY.call("increment", null, counter::incrementAndGet);
 *     HISTORY.call("increment", null, counter::incrementAndGet);
 * }
 *
 * @AfterAll
 * static void linearizable() {
 *     HISTORY.assertLinearizable(SequentialSpec.of(
 *             () -> new int[1], int[]::clone, (state, op, arg) -> ++state[0]));
 * }
 * }</pre>
 *
 * <p>Each round is checked on its own, against a fresh model: {@link #subject()} hands every worker of
 * a round the same instance, and a new one in the next round. One search takes at most 64
 * operations, because it is exponential in the worst case; a round the search cannot decide within
 * its budget fails rather than passes. A round may record more, up to
 * {@value #MAX_OPERATIONS_PER_ROUND}, when it is checked with a {@link Partition}: each independent
 * object, such as one key of a map, is then searched on its own (#933). The design is in
 * {@code docs/analysis/linearizability-checking.md}.
 *
 * @param <S> the type of the object under test
 * @since 1.13.0
 */
@API(status = Status.EXPERIMENTAL, since = "1.13.0")
public final class OperationHistory<S> {

    /**
     * One sequence for every history, so tickets from any two calls compare in real time: a call
     * whose response ticket is below another's invocation ticket returned before the other began.
     */
    private static final AtomicLong TICKETS = new AtomicLong();

    /**
     * The most operations one round may record. A whole-history check still searches at most 64;
     * more are useful only to {@link #assertLinearizable(SequentialSpec, Partition)}, which searches
     * each partition on its own.
     */
    public static final int MAX_OPERATIONS_PER_ROUND = 1024;

    /** The bucket for calls made outside an {@code @AsyncTest} round. */
    private static final Object NO_ROUND = new Object();

    private final Supplier<? extends S> fresh;
    private final Map<Object, S> subjects = new ConcurrentHashMap<>();
    private final Map<Object, RoundLog> rounds = new ConcurrentHashMap<>();
    private final List<Declared<S>> declared = new CopyOnWriteArrayList<>();

    /**
     * Each worker's draw stream, per round and slot, so a second {@link #generate} call in the
     * same round continues the stream instead of drawing the first operations again.
     */
    private final Map<List<Object>, RandomGenerator> streams = new ConcurrentHashMap<>();

    /**
     * The name a failed check is reported under; {@code DetectorTrust} grades it FACT, because a
     * history no order can explain is a proof, not a pattern match.
     */
    static final String FINDING = "Linearizability";

    /** The per-round check {@link #verifiedAgainst} installed, run at the end of each run. */
    private volatile @Nullable Consumer<RoundLog> verifier;

    private OperationHistory(Supplier<? extends S> fresh) {
        this.fresh = fresh;
    }

    /**
     * {@return a history whose {@link #subject()} builds one object per round from {@code fresh}}
     *
     * @param <S>   the type of the object under test
     * @param fresh a new object under test, in its initial state
     */
    public static <S> OperationHistory<S> of(Supplier<? extends S> fresh) {
        return new OperationHistory<>(Objects.requireNonNull(fresh, "fresh"));
    }

    /**
     * {@return the current round's object under test, built on first use}
     *
     * <p>Every worker of a round gets the same instance, and the next round gets a new one, so
     * each round's history starts from the state {@link SequentialSpec#initial()} describes.
     */
    public S subject() {
        return subjects.computeIfAbsent(roundKey(), key -> fresh.get());
    }

    /**
     * Calls {@code action} and records it as one operation: a ticket before, the result and a
     * ticket after. An action that throws is recorded as a call with no response, which the
     * check may place anywhere after it began, or leave out, and the exception propagates.
     *
     * @param <R>       the action's result type
     * @param operation the operation's name, as the {@link SequentialSpec} knows it
     * @param argument  the operation's argument, passed to the spec, or {@code null}
     * @param action    the call on the object under test
     * @return what {@code action} returned
     * @throws IllegalStateException when the round has already recorded
     *                               {@value #MAX_OPERATIONS_PER_ROUND} operations
     */
    public <R> R call(String operation, @Nullable Object argument, Supplier<R> action) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(action, "action");
        RoundLog log = rounds.computeIfAbsent(roundKey(), RoundLog::new);
        AsyncTestContext context = log.context;
        if (verifier != null && context != null) {
            context.addRunCheck(this, () -> findings(context));
        }
        String thread = Thread.currentThread().getName();
        long invoke = TICKETS.incrementAndGet();
        R result;
        try {
            result = action.get();
        } catch (RuntimeException | Error e) {
            log.add(new Op(thread, operation, argument, null, invoke, LinearizabilityChecker.NO_RESPONSE));
            throw e;
        }
        long response = TICKETS.incrementAndGet();
        log.add(new Op(thread, operation, argument, result, invoke, response));
        return result;
    }

    /**
     * {@link #call} for an operation that returns nothing; it is recorded with a {@code null}
     * result, which the spec's step must also return.
     *
     * @param operation the operation's name
     * @param argument  its argument, or {@code null}
     * @param action    the call on the object under test
     */
    public void run(String operation, @Nullable Object argument, Runnable action) {
        Objects.requireNonNull(action, "action");
        call(operation, argument, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Declares an operation {@link #generate(int)} may draw (#935). Declare every operation before
     * the run; the order of declaration is part of what a replay seed reproduces.
     *
     * <pre>{@code
     * private static final OperationHistory<ConcurrentLinkedQueue<Integer>> HISTORY =
     *         OperationHistory.of(ConcurrentLinkedQueue<Integer>::new)
     *                 .operation("offer", random -> random.nextInt(100), (queue, value) -> queue.offer((Integer) value))
     *                 .operation("poll", random -> null, (queue, unused) -> queue.poll());
     *
     * @AsyncTest(threads = 4, invocations = 50)
     * void queue() {
     *     HISTORY.generate(8);   // each worker draws its own 8 operations
     * }
     * }</pre>
     *
     * @param name     the operation's name, as the {@link SequentialSpec} knows it
     * @param argument draws the operation's argument; may return {@code null}
     * @param action   performs the operation on the round's subject with that argument
     * @return this history, to declare the next operation
     * @since 1.13.0
     */
    public OperationHistory<S> operation(String name, Function<? super RandomGenerator, ?> argument,
                                         BiFunction<? super S, Object, ?> action) {
        declared.add(new Declared<>(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(argument, "argument"), Objects.requireNonNull(action, "action")));
        return this;
    }

    /**
     * Draws {@code operations} operations from those declared with {@link #operation} and performs
     * each on the round's {@link #subject()}, recording it as {@link #call} does (#935).
     *
     * <p>The test author names what a worker may do, not the order: each worker of each round draws
     * from its own stream, seeded by the round's {@link AsyncTestContext#replaySeed()}, the round
     * number and the worker's slot. A failing scenario is reproduced by pasting the seed the runner
     * prints into {@code @AsyncTest(replaySeed = ...)}, which every round then shares.
     *
     * @param operations how many operations this worker performs in this round, not negative
     * @throws IllegalStateException when no operation was declared
     * @since 1.13.0
     */
    public void generate(int operations) {
        if (operations < 0) {
            throw new IllegalArgumentException("operations must not be negative, was " + operations);
        }
        List<Declared<S>> choices = List.copyOf(declared);
        if (choices.isEmpty()) {
            throw new IllegalStateException("generate() draws from the operations declared with "
                    + "operation(name, argument, action), and none were declared");
        }
        Object round = roundKey();
        int slot = WorkerSlot.current();
        RandomGenerator random = streams.computeIfAbsent(List.of(round, slot),
                key -> new SplittableRandom(drawSeed(round, slot)));
        S subject = subject();
        for (int i = 0; i < operations; i++) {
            Declared<S> op = choices.get(random.nextInt(choices.size()));
            Object arg = op.argument().apply(random);
            call(op.name(), arg, () -> op.action().apply(subject, arg));
        }
    }

    /**
     * The seed of one worker's stream: one per (replay seed, round, worker slot). Only that worker
     * draws from it, so the stream needs no lock.
     */
    private static long drawSeed(Object round, int workerSlot) {
        long number = round instanceof AsyncTestContext.Round r ? r.number() : 0;
        long slot = workerSlot + 1L;
        return AsyncTestContext.replaySeed() ^ (number * 0x9E3779B97F4A7C15L) ^ (slot * 0xC2B2AE3D27D4EB4FL);
    }

    /** One operation {@link #generate} may draw. */
    private record Declared<S>(String name, Function<? super RandomGenerator, ?> argument,
                               BiFunction<? super S, Object, ?> action) { }

    /**
     * Checks each round against {@code spec} at the end of its run, and reports a round with no
     * linearization as a finding named {@code Linearizability} (#934).
     *
     * <p>The finding goes where a detector's does: the run's report, every listener and
     * {@link AsyncFindings}, and the {@code failOn} gate, at severity HIGH and trust tier FACT. A
     * test needs no {@code @AfterAll} assertion, and a suite that gates on findings gates on this
     * one too. {@link #assertLinearizable} keeps working beside it.
     *
     * @param <M>  the model's state
     * @param spec the sequential behaviour the object must be explainable by
     * @return this history
     * @since 1.13.0
     */
    public <M> OperationHistory<S> verifiedAgainst(SequentialSpec<M> spec) {
        Objects.requireNonNull(spec, "spec");
        verifier = log -> checkWhole(log, spec);
        return this;
    }

    /**
     * As {@link #verifiedAgainst(SequentialSpec)}, one partition at a time, as
     * {@link #assertLinearizable(SequentialSpec, Partition)} checks.
     *
     * @param <M>       the model of one partition
     * @param spec      the sequential behaviour of one partition
     * @param partition the partition an operation belongs to
     * @return this history
     * @since 1.13.0
     */
    public <M> OperationHistory<S> verifiedAgainst(SequentialSpec<M> spec, Partition partition) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(partition, "partition");
        verifier = log -> checkPartitioned(log, spec, partition);
        return this;
    }

    /**
     * {@return a finding per round of {@code context}'s run that fails the installed check}
     *
     * <p>Runs on the runner thread at analysis, after the workers have finished. It never throws:
     * a spec that throws is reported as a round it could not check, because an exception here
     * would cost the run every detector's findings.
     */
    private List<Violation> findings(AsyncTestContext context) {
        Consumer<RoundLog> check = verifier;
        if (check == null) {
            return List.of();
        }
        List<RoundLog> logs = new ArrayList<>();
        for (RoundLog log : rounds.values()) {
            if (log.context == context) { // NOPMD CompareObjectsWithEquals - the run is an identity
                logs.add(log);
            }
        }
        logs.sort(Comparator.comparingInt(log -> log.number));
        List<Violation> found = new ArrayList<>();
        for (RoundLog log : logs) {
            try {
                check.accept(log);
            } catch (AssertionError e) {
                found.add(new Violation(FINDING, IssueSeverity.HIGH, String.valueOf(e.getMessage()), List.of(), Map.of(), java.time.Instant.now()));
            } catch (RuntimeException e) {
                found.add(new Violation(FINDING, IssueSeverity.HIGH, roundName(log.number)
                        + " could not be checked: the spec threw " + e, List.of(), Map.of(), java.time.Instant.now()));
            }
        }
        return found;
    }

    /**
     * Checks every recorded round against {@code spec}.
     *
     * @param <M>  the model's state
     * @param spec the sequential behaviour the object must be explainable by
     * @throws AssertionError for the first round with no linearization, listing its operations;
     *                        for a round the search could not decide within its budget; for a
     *                        round of more than 64 operations, which one search cannot take; and
     *                        when nothing was recorded, since a check over no operations proves
     *                        nothing
     */
    public <M> void assertLinearizable(SequentialSpec<M> spec) {
        Objects.requireNonNull(spec, "spec");
        for (RoundLog log : recordedRounds()) {
            checkWhole(log, spec);
        }
    }

    private static <M> void checkWhole(RoundLog log, SequentialSpec<M> spec) {
        List<Op> ops = log.snapshot();
        if (ops.size() > LinearizabilityChecker.MAX_OPERATIONS) {
            throw new AssertionError(roundName(log.number) + " recorded " + ops.size()
                    + " operations; one search takes at most " + LinearizabilityChecker.MAX_OPERATIONS
                    + ". Partition the history, assertLinearizable(spec, partition), to search each "
                    + "independent object (one key of a map, one queue of several) on its own.");
        }
        check(roundName(log.number), ops, spec);
    }

    /**
     * Checks every recorded round one partition at a time (#933).
     *
     * <p>{@code partition} names the independent object each operation touches, typically the key of
     * a map; each partition's operations are searched on their own against a fresh
     * {@link SequentialSpec#initial()}, so {@code spec} models one partition, not the whole object.
     * Linearizability is local: a history of independent objects is linearizable exactly when each
     * object's own history is (Herlihy and Wing, 1990). It is the caller's claim that the partitions
     * are independent; operations that touch several, such as a map's {@code size()}, cannot be
     * checked this way. A round may then record up to {@value #MAX_OPERATIONS_PER_ROUND} operations
     * as long as no partition holds more than 64.
     *
     * @param <M>       the model of one partition
     * @param spec      the sequential behaviour of one partition
     * @param partition the partition an operation belongs to; never {@code null}
     * @throws AssertionError as {@link #assertLinearizable(SequentialSpec)}, naming the partition
     * @throws NullPointerException when {@code partition} returns {@code null} for an operation
     */
    public <M> void assertLinearizable(SequentialSpec<M> spec, Partition partition) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(partition, "partition");
        for (RoundLog log : recordedRounds()) {
            checkPartitioned(log, spec, partition);
        }
    }

    private static <M> void checkPartitioned(RoundLog log, SequentialSpec<M> spec, Partition partition) {
        Map<Object, List<Op>> parts = new LinkedHashMap<>();
        for (Op op : log.snapshot()) {
            Object key = Objects.requireNonNull(partition.keyOf(op.operation(), op.argument()),
                    () -> "the partition of " + op.operation() + "(" + op.argument() + ") is null");
            parts.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
        }
        for (Map.Entry<Object, List<Op>> part : parts.entrySet()) {
            String name = roundName(log.number) + ", partition " + part.getKey() + ",";
            if (part.getValue().size() > LinearizabilityChecker.MAX_OPERATIONS) {
                throw new AssertionError(name + " recorded " + part.getValue().size()
                        + " operations; one search takes at most " + LinearizabilityChecker.MAX_OPERATIONS
                        + ". Partition more finely, or record fewer operations per round.");
            }
            check(name, part.getValue(), spec);
        }
    }

    /**
     * Names the independent object an operation touches, for
     * {@link OperationHistory#assertLinearizable(SequentialSpec, Partition)}.
     *
     * @since 1.13.0
     */
    @FunctionalInterface
    @API(status = Status.EXPERIMENTAL, since = "1.13.0")
    public interface Partition {
        /**
         * {@return the partition {@code operation} belongs to, compared by {@code equals}; never
         * {@code null}}
         *
         * @param operation the operation's name, as recorded
         * @param argument  its argument, as recorded
         */
        Object keyOf(String operation, @Nullable Object argument);
    }

    private List<RoundLog> recordedRounds() {
        List<RoundLog> logs = new ArrayList<>(rounds.values());
        if (logs.isEmpty()) {
            throw new AssertionError("No operations were recorded, so there is nothing to check: "
                    + "record them with call() or run() from the @AsyncTest body.");
        }
        logs.sort(Comparator.comparingInt(log -> log.number));
        return logs;
    }

    private static <M> void check(String name, List<Op> ops, SequentialSpec<M> spec) {
        Verdict verdict = LinearizabilityChecker.check(ops, spec, LinearizabilityChecker.DEFAULT_BUDGET);
        if (verdict == Verdict.NOT_LINEARIZABLE) {
            throw new AssertionError(describe(name, ops,
                    "is not linearizable: no order of its " + ops.size()
                            + " operations that respects real time gives the results the workers saw."));
        }
        if (verdict == Verdict.UNDECIDED) {
            throw new AssertionError(describe(name, ops,
                    "could not be decided within " + LinearizabilityChecker.DEFAULT_BUDGET
                            + " search steps; record fewer operations per round."));
        }
    }

    private static String roundName(int round) {
        return round > 0 ? "Round " + round : "The history recorded outside a round";
    }

    /** {@return how many rounds have recorded at least one operation} */
    public int rounds() {
        return rounds.size();
    }

    private static Object roundKey() {
        AsyncTestContext.Round round = AsyncTestContext.currentRound();
        return round != null ? round : NO_ROUND;
    }

    private static String describe(String name, List<Op> ops, String verdict) {
        StringBuilder sb = new StringBuilder(name).append(' ').append(verdict);
        ops.stream().sorted(Comparator.comparingLong(Op::invoke)).forEach(op -> {
            sb.append("\n  [").append(op.thread()).append("] ").append(op.operation())
                    .append('(').append(op.argument() == null ? "" : op.argument()).append(") -> ");
            if (op.pending()) {
                sb.append("threw, no response   (invoked #").append(op.invoke()).append(')');
            } else {
                sb.append(op.result()).append("   (invoked #").append(op.invoke())
                        .append(", returned #").append(op.response()).append(')');
            }
        });
        return sb.toString();
    }

    /** One round's operations, bounded at {@link #MAX_OPERATIONS_PER_ROUND}. */
    private static final class RoundLog {
        final int number;
        /** The run the round belongs to, or {@code null} outside one. */
        final @Nullable AsyncTestContext context;
        private final List<Op> ops = new ArrayList<>();

        RoundLog(Object key) {
            this.number = key instanceof AsyncTestContext.Round round ? round.number() : 0;
            this.context = AsyncTestContext.currentContext();
        }

        synchronized void add(Op op) {
            if (ops.size() >= MAX_OPERATIONS_PER_ROUND) {
                throw new IllegalStateException("A round may record at most "
                        + MAX_OPERATIONS_PER_ROUND + " operations, and one search takes at most "
                        + LinearizabilityChecker.MAX_OPERATIONS + "; record fewer operations per "
                        + "worker, or run fewer threads.");
            }
            ops.add(op);
        }

        synchronized List<Op> snapshot() {
            return List.copyOf(ops);
        }
    }
}
