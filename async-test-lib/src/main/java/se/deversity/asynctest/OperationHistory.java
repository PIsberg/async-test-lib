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

    /**
     * Set only on a probe {@link #shrink} runs: each worker's operations, by slot, which
     * {@link #generate} then performs instead of drawing (#1025).
     */
    private volatile @Nullable Map<Integer, List<Drawn>> script;

    /** The most re-runs one shrink makes, so a large scenario cannot hold the check up for long. */
    static final int MAX_SHRINK_RUNS = 2_000;

    /**
     * How many schedules a candidate scenario is tried under: the round's own seed, then seeds
     * derived from it. Leaving an operation out moves every later step, so the interleaving that
     * failed is not the one a smaller scenario replays under the same seed; trying a few is what
     * lets the search keep a smaller scenario that fails under another (#1025).
     */
    static final int SCHEDULES_PER_CANDIDATE = 64;

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
        S subject = subject();
        Map<Integer, List<Drawn>> replay = script;
        if (replay != null) {
            for (Drawn step : replay.getOrDefault(slot, List.of())) {
                Declared<S> op = choices.get(step.choice());
                call(op.name(), step.argument(), () -> op.action().apply(subject, step.argument()));
            }
            return;
        }
        RandomGenerator random = streams.computeIfAbsent(List.of(round, slot),
                key -> new SplittableRandom(drawSeed(round, slot)));
        RoundLog log = rounds.computeIfAbsent(round, RoundLog::new);
        for (int i = 0; i < operations; i++) {
            int choice = random.nextInt(choices.size());
            Declared<S> op = choices.get(choice);
            Object arg = op.argument().apply(random);
            log.drew(slot, new Drawn(choice, arg));
            call(op.name(), arg, () -> op.action().apply(subject, arg));
        }
    }

    /** One drawn operation: which declared operation, and its argument (#1025). */
    private record Drawn(int choice, @Nullable Object argument) { }

    /**
     * One drawn operation of one worker. {@code position} is its place in the whole scenario, so two
     * equal operations of one worker, such as two {@code increment()}s, stay two steps when a
     * subset is removed from a candidate.
     */
    private record Step(int position, int slot, Drawn drawn) { }

    /**
     * Shrinks the failing round {@code log} by re-running its drawn scenario with operations left
     * out, and returns the smallest one {@code check} still fails, described; {@code null} when
     * the round drew nothing to replay (#1025).
     *
     * <p>Each attempt is one {@link AsyncTestRunner#run} of a probe history holding the same
     * declared operations, under a controlled schedule, so a subset and a seed replay one
     * interleaving and the user's own history records nothing. A candidate fails when it fails under
     * any of {@value #SCHEDULES_PER_CANDIDATE} seeds, the round's own first. The search is delta
     * debugging over the flat list of drawn operations, each worker's kept in its order, and stops
     * after {@value #MAX_SHRINK_RUNS} runs with the smallest failing scenario found so far.
     */
    private @Nullable String shrink(RoundLog log, Consumer<RoundLog> check) {
        List<Step> all = new ArrayList<>();
        log.scenario().forEach((slot, drawn) -> drawn.forEach(step -> all.add(new Step(all.size(), slot, step))));
        if (all.isEmpty() || log.workers <= 0 || AsyncTestContext.currentContext() != null) {
            return null;
        }
        int[] runs = {0};
        long[] failingSeed = {log.seed};
        java.util.function.Predicate<List<Step>> fails = subset -> {
            for (int i = 0; i < SCHEDULES_PER_CANDIDATE && runs[0] < MAX_SHRINK_RUNS; i++) {
                long seed = log.seed + i * 0x9E3779B97F4A7C15L;
                runs[0]++;
                if (failsWhenReplayed(subset, log, seed, check)) {
                    failingSeed[0] = seed;
                    return true;
                }
            }
            return false;
        };
        if (!fails.test(all)) {
            return "Re-running the " + all.size() + " drawn operations under the round's seed and a "
                    + "controlled schedule did not fail, so the scenario was not shrunk; a failure "
                    + "that depends on timing outside the schedule does not replay.";
        }
        List<Step> minimal = all;
        int parts = 2;
        while (minimal.size() >= 2 && runs[0] < MAX_SHRINK_RUNS) {
            List<List<Step>> chunks = split(minimal, parts);
            List<Step> next = null;
            for (List<Step> chunk : chunks) {
                if (runs[0] < MAX_SHRINK_RUNS && fails.test(chunk)) {
                    next = chunk;
                    parts = 2;
                    break;
                }
            }
            if (next == null) {
                for (List<Step> chunk : chunks) {
                    List<Step> rest = new ArrayList<>(minimal);
                    rest.removeAll(chunk);
                    if (runs[0] < MAX_SHRINK_RUNS && fails.test(rest)) {
                        next = rest;
                        parts = Math.max(parts - 1, 2);
                        break;
                    }
                }
            }
            if (next != null) {
                minimal = next;
            } else if (parts >= minimal.size()) {
                break;
            } else {
                parts = Math.min(minimal.size(), parts * 2);
            }
        }
        StringBuilder sb = new StringBuilder("Shrunk by re-running the drawn scenario under the round's seed and a "
                + "controlled schedule: ").append(minimal.size()).append(" of the ").append(all.size())
                .append(" drawn operations still fail, under replaySeed = ").append(failingSeed[0]).append("L");
        if (runs[0] >= MAX_SHRINK_RUNS) {
            sb.append(" (the search stopped after ").append(MAX_SHRINK_RUNS).append(" runs)");
        }
        sb.append(':');
        List<Declared<S>> choices = List.copyOf(declared);
        Map<Integer, List<String>> byWorker = new java.util.TreeMap<>();
        for (Step step : minimal) {
            Object arg = step.drawn().argument();
            byWorker.computeIfAbsent(step.slot(), k -> new ArrayList<>())
                    .add(choices.get(step.drawn().choice()).name() + "(" + (arg == null ? "" : arg) + ")");
        }
        byWorker.forEach((slot, ops) -> sb.append("\n  worker ").append(slot).append(": ")
                .append(String.join(", ", ops)));
        return sb.toString();
    }

    /** {@return the list cut into {@code parts} contiguous pieces, as even as they come} */
    private static List<List<Step>> split(List<Step> steps, int parts) {
        List<List<Step>> out = new ArrayList<>();
        int size = steps.size();
        for (int i = 0; i < parts; i++) {
            int from = i * size / parts;
            int to = (i + 1) * size / parts;
            if (from < to) {
                out.add(steps.subList(from, to));
            }
        }
        return out;
    }

    /** {@return whether {@code check} fails the one round of a probe run that performs {@code steps}} */
    private boolean failsWhenReplayed(List<Step> steps, RoundLog failing, long seed, Consumer<RoundLog> check) {
        // Only the workers the candidate uses, renumbered from 0: an idle worker still takes part in
        // the schedule's priorities, so leaving it in changes which interleavings a seed reaches.
        Map<Integer, List<Drawn>> bySlot = new java.util.TreeMap<>();
        for (Step step : steps) {
            bySlot.computeIfAbsent(step.slot(), k -> new ArrayList<>()).add(step.drawn());
        }
        Map<Integer, List<Drawn>> replay = new java.util.TreeMap<>();
        for (List<Drawn> ops : bySlot.values()) {
            replay.put(replay.size(), ops);
        }
        return replayFailure(replay, seed, failing.licenseMockMode, check) != null;
    }

    /**
     * {@return what {@code check} reports for the one round of a probe run that performs
     * {@code replay}, worker slot by slot, under {@code seed} and a controlled schedule, or
     * {@code null} when the round is linearizable or the run could not decide it}
     */
    private @Nullable NotLinearizable replayFailure(Map<Integer, List<Drawn>> replay, long seed,
                                                   boolean licenseMockMode, Consumer<RoundLog> check) {
        OperationHistory<S> probe = new OperationHistory<>(fresh);
        probe.declared.addAll(declared);
        probe.script = replay;
        try {
            AsyncTestRunner.run(AsyncTestConfig.builder().threads(Math.max(1, replay.size())).invocations(1)
                    .replaySeed(seed).controlledSchedule(true).timeoutMs(10_000)
                    .licenseMockMode(licenseMockMode).build(), () -> probe.generate(0));
        } catch (Throwable notAFailingRun) { // NOPMD - a run that throws is not a linearizability failure
            return null;
        }
        for (RoundLog log : probe.rounds.values()) {
            try {
                check.accept(log);
            } catch (NotLinearizable fails) {
                return fails;
            } catch (AssertionError other) { // NOPMD - undecided or oversized: not this failure
                return null;
            }
        }
        return null;
    }

    /** How many schedules each enumerated scenario is run under: seeds 1 to this (#1082). */
    static final int SCHEDULES_PER_SCENARIO = 32;

    /** The most runs one enumeration may take; a larger space is refused, not partly searched. */
    static final int MAX_ENUMERATION_RUNS = 20_000;

    /**
     * Runs every scenario of up to {@code operationsPerWorker} declared operations on each of
     * {@code workers} workers, smallest first, and checks each against {@code spec} (#1082).
     *
     * <p>Where {@link #generate} draws one scenario per worker per round, this enumerates them, as
     * Lincheck does: a race that only a particular small scenario exposes is found whatever a draw
     * would have picked. Each scenario runs as one {@link AsyncTestRunner#run} round with a
     * controlled schedule under {@value #SCHEDULES_PER_SCENARIO} seeds, on a probe history that
     * records nothing into this one. An operation's argument is drawn once per worker and position
     * from a fixed seed, so the space is the operations' order, not their arguments. Call it from a
     * plain test or {@code @AfterAll}, never from inside an {@code @AsyncTest} round, where a run
     * cannot start; its runs pass the licence gate the way the test's own runs do, through
     * {@code -Dlicense.mock.mode} or a key.
     *
     * @param <M>                 the model's state
     * @param spec                the sequential behaviour the object must be explainable by
     * @param workers             how many workers run at once, at least 1
     * @param operationsPerWorker the longest sequence a worker runs, at least 1
     * @return what the search covered, for the test's log: every scenario was linearizable
     * @throws AssertionError            for the first scenario with no linearization, listing it
     *                                   per worker with the seed that reproduces it
     * @throws IllegalArgumentException  when the space takes more than {@value #MAX_ENUMERATION_RUNS}
     *                                   runs: a search that stopped part way would read as a pass it
     *                                   is not
     * @throws IllegalStateException     when no operation was declared
     * @since 1.13.3
     */
    public <M> String assertLinearizableForAllScenarios(SequentialSpec<M> spec, int workers,
                                                        int operationsPerWorker) {
        return assertLinearizableForAllScenarios(spec, workers, operationsPerWorker, 1);
    }

    /**
     * Runs every scenario of up to {@code operationsPerWorker} declared operations on each of
     * {@code workers} workers across {@code argumentDrawsPerScenario} argument draws, smallest
     * first, and checks each against {@code spec} (#1082).
     *
     * @param <M>                      the model's state
     * @param spec                     the sequential behaviour the object must be explainable by
     * @param workers                  how many workers run at once, at least 1
     * @param operationsPerWorker      the longest sequence a worker runs, at least 1
     * @param argumentDrawsPerScenario how many distinct argument tuples to draw per scenario, at least 1
     * @return what the search covered, for the test's log
     * @since 1.13.3
     */
    public <M> String assertLinearizableForAllScenarios(SequentialSpec<M> spec, int workers,
                                                        int operationsPerWorker, int argumentDrawsPerScenario) {
        if (argumentDrawsPerScenario < 1) {
            throw new IllegalArgumentException("argumentDrawsPerScenario must be at least 1, was "
                    + argumentDrawsPerScenario);
        }
        Objects.requireNonNull(spec, "spec");
        if (workers < 1 || operationsPerWorker < 1) {
            throw new IllegalArgumentException("workers and operationsPerWorker must be at least 1, were "
                    + workers + " and " + operationsPerWorker);
        }
        List<Declared<S>> choices = List.copyOf(declared);
        if (choices.isEmpty()) {
            throw new IllegalStateException("the scenarios are built from the operations declared with "
                    + "operation(name, argument, action), and none were declared");
        }
        List<List<Integer>> sequences = sequencesUpTo(choices.size(), operationsPerWorker);
        long scenarios = 1;
        try {
            for (int i = 0; i < workers; i++) {
                scenarios = Math.multiplyExact(scenarios, sequences.size());
            }
        } catch (ArithmeticException tooMany) {
            scenarios = Long.MAX_VALUE;
        }
        long totalScenarios = scenarios;
        if (totalScenarios != Long.MAX_VALUE) {
            try {
                totalScenarios = Math.multiplyExact(totalScenarios, argumentDrawsPerScenario);
            } catch (ArithmeticException tooMany) {
                totalScenarios = Long.MAX_VALUE;
            }
        }
        if (totalScenarios == Long.MAX_VALUE || totalScenarios > MAX_ENUMERATION_RUNS / SCHEDULES_PER_SCENARIO) {
            throw new IllegalArgumentException("up to " + operationsPerWorker + " operations on each of "
                    + workers + " workers is " + (scenarios == Long.MAX_VALUE ? "too many" : scenarios)
                    + " scenarios, each run under " + SCHEDULES_PER_SCENARIO + " schedules, more than the "
                    + MAX_ENUMERATION_RUNS + " runs one search takes; lower workers or operationsPerWorker");
        }
        List<int[]> order = new ArrayList<>();
        int[] tuple = new int[workers];
        for (long n = 0; n < scenarios; n++) {
            order.add(tuple.clone());
            for (int w = workers - 1; w >= 0; w--) {
                tuple[w]++;
                if (tuple[w] < sequences.size()) {
                    break;
                }
                tuple[w] = 0;
            }
        }
        order.sort(Comparator.comparingInt(t -> {
            int total = 0;
            for (int index : t) {
                total += sequences.get(index).size();
            }
            return total;
        }));
        Consumer<RoundLog> check = round -> checkWhole(round, spec);
        for (int[] scenario : order) {
          for (int draw = 0; draw < argumentDrawsPerScenario; draw++) {
            Map<Integer, List<Drawn>> replay = new java.util.TreeMap<>();
            for (int w = 0; w < workers; w++) {
                List<Integer> ops = sequences.get(scenario[w]);
                if (ops.isEmpty()) {
                    continue;
                }
                List<Drawn> steps = new ArrayList<>();
                for (int position = 0; position < ops.size(); position++) {
                    RandomGenerator random = new SplittableRandom(
                            0x9E3779B97F4A7C15L * (w + 1) + position + draw * 0xC2B2AE3D27D4EB4FL);
                    int choice = ops.get(position);
                    steps.add(new Drawn(choice, choices.get(choice).argument().apply(random)));
                }
                replay.put(w, steps);
            }
            if (replay.size() < 2) {
                continue; // one worker alone is sequential, and a sequential run is linearizable
            }
            Map<Integer, List<Drawn>> renumbered = new java.util.TreeMap<>();
            replay.values().forEach(steps -> renumbered.put(renumbered.size(), steps));
            for (long seed = 1; seed <= SCHEDULES_PER_SCENARIO; seed++) {
                NotLinearizable failure = replayFailure(renumbered, seed, false, check);
                if (failure != null) {
                    StringBuilder sb = new StringBuilder("An enumerated scenario is not linearizable, under replaySeed = ")
                            .append(seed).append("L, searching smallest first:");
                    replay.forEach((slot, steps) -> {
                        List<String> names = new ArrayList<>();
                        for (Drawn step : steps) {
                            Object arg = step.argument();
                            names.add(choices.get(step.choice()).name() + "(" + (arg == null ? "" : arg) + ")");
                        }
                        sb.append("\n  worker ").append(slot).append(": ").append(String.join(", ", names));
                    });
                    throw new AssertionError(sb.append("\n\n").append(failure.getMessage()).toString(), failure);
                }
            }
        }
          }
        return "linearizable over all " + scenarios + " scenarios of up to " + operationsPerWorker
                + " operations on each of " + workers + " workers, each run under " + SCHEDULES_PER_SCENARIO
                + " schedules";
    }

    /** {@return every sequence of 0 to {@code longest} choices from {@code choices}, shortest first} */
    private static List<List<Integer>> sequencesUpTo(int choices, int longest) {
        List<List<Integer>> all = new ArrayList<>();
        all.add(List.of());
        List<List<Integer>> previous = List.of(List.of());
        for (int length = 1; length <= longest; length++) {
            List<List<Integer>> next = new ArrayList<>();
            for (List<Integer> prefix : previous) {
                for (int choice = 0; choice < choices; choice++) {
                    List<Integer> extended = new ArrayList<>(prefix);
                    extended.add(choice);
                    next.add(List.copyOf(extended));
                }
            }
            all.addAll(next);
            previous = next;
        }
        return all;
    }

    /**
     * The one failure {@link #shrink} looks for: a round no order of its operations explains. An
     * {@code AssertionError} on purpose, since it reaches the test as the check's failure.
     */
    private static final class NotLinearizable extends AssertionError { // NOPMD DoNotExtendJavaLangError - a check's failure
        private static final long serialVersionUID = 1L;

        NotLinearizable(String message) {
            super(message);
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
        boolean shrunkOne = false;
        for (RoundLog log : logs) {
            try {
                check.accept(log);
            } catch (NotLinearizable e) {
                String message = String.valueOf(e.getMessage());
                if (!shrunkOne) {
                    shrunkOne = true;
                    String shrunk = shrink(log, check);
                    if (shrunk != null) {
                        message = message + "\n\n" + shrunk;
                    }
                }
                found.add(new Violation(FINDING, IssueSeverity.HIGH, message, List.of(), Map.of(), java.time.Instant.now()));
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
     * @throws AssertionError for the first round with no linearization, listing the shortest prefix
     *                        of its operations that already has none, and naming the response that
     *                        ended it, and for a round drawn with {@link #generate}, the smallest
     *                        drawn scenario that still fails when re-run (#1025); for a round the
     *                        search could not decide within its budget; for a round of more than 64
     *                        operations, which one search cannot take; and when nothing was
     *                        recorded, since a check over no operations proves nothing
     */
    public <M> void assertLinearizable(SequentialSpec<M> spec) {
        Objects.requireNonNull(spec, "spec");
        for (RoundLog log : recordedRounds()) {
            checkShrinking(log, round -> checkWhole(round, spec));
        }
    }

    /**
     * Runs {@code check} on {@code log}, and when the round is not linearizable and drew its
     * operations, adds the smallest drawn scenario that still fails to the error (#1025).
     */
    private void checkShrinking(RoundLog log, Consumer<RoundLog> check) {
        try {
            check.accept(log);
        } catch (NotLinearizable e) {
            String shrunk = shrink(log, check);
            if (shrunk == null) {
                throw e;
            }
            throw new AssertionError(e.getMessage() + "\n\n" + shrunk, e);
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
            checkShrinking(log, round -> checkPartitioned(round, spec, partition));
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
            String head = "is not linearizable: no order of its " + ops.size()
                    + " operations that respects real time gives the results the workers saw.";
            long cut = LinearizabilityChecker.shortestViolatingCut(ops, spec, LinearizabilityChecker.DEFAULT_BUDGET);
            List<Op> shown = cut < 0 ? ops : ops.stream().filter(op -> op.invoke() < cut).toList();
            if (shown.size() == ops.size()) {
                throw new NotLinearizable(describe(name, ops, head, Long.MAX_VALUE));
            }
            Op last = ops.stream().filter(op -> op.response() == cut).findFirst().orElseThrow();
            throw new NotLinearizable(describe(name, shown, head + " The shortest prefix with no such order"
                    + " holds " + shown.size() + " of its " + ops.size() + " operations and ends at #" + cut
                    + ", when [" + last.thread() + "] " + last.operation() + "("
                    + (last.argument() == null ? "" : last.argument()) + ") returned " + last.result()
                    + "; the operations after it are left out:", cut));
        }
        if (verdict == Verdict.UNDECIDED) {
            throw new AssertionError(describe(name, ops,
                    "could not be decided within " + LinearizabilityChecker.DEFAULT_BUDGET
                            + " search steps; record fewer operations per round.", Long.MAX_VALUE));
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

    /** Lists {@code ops} in invocation order; one that returned after {@code cut} is marked as such. */
    private static String describe(String name, List<Op> ops, String verdict, long cut) {
        StringBuilder sb = new StringBuilder(name).append(' ').append(verdict);
        ops.stream().sorted(Comparator.comparingLong(Op::invoke)).forEach(op -> {
            sb.append("\n  [").append(op.thread()).append("] ").append(op.operation())
                    .append('(').append(op.argument() == null ? "" : op.argument()).append(") -> ");
            if (op.pending()) {
                sb.append("threw, no response   (invoked #").append(op.invoke()).append(')');
            } else {
                sb.append(op.result()).append("   (invoked #").append(op.invoke())
                        .append(", returned #").append(op.response())
                        .append(op.response() > cut ? ", after the cut, so still running at it)" : ")");
            }
        });
        return sb.toString();
    }

    /** One round's operations, bounded at {@link #MAX_OPERATIONS_PER_ROUND}. */
    private static final class RoundLog {
        final int number;
        /** The run the round belongs to, or {@code null} outside one. */
        final @Nullable AsyncTestContext context;
        /** The round's seed, workers and licence mode, which a re-run of its scenario repeats. */
        final long seed;
        final int workers;
        final boolean licenseMockMode;
        private final List<Op> ops = new ArrayList<>();
        /** What each worker drew through {@link OperationHistory#generate}, by slot (#1025). */
        private final Map<Integer, List<Drawn>> drawn = new java.util.TreeMap<>();

        RoundLog(Object key) {
            this.number = key instanceof AsyncTestContext.Round round ? round.number() : 0;
            this.workers = key instanceof AsyncTestContext.Round round ? round.workers() : 0;
            this.context = AsyncTestContext.currentContext();
            this.seed = AsyncTestContext.replaySeed();
            this.licenseMockMode = context != null && context.licenseMockMode;
        }

        synchronized void drew(int slot, Drawn step) {
            drawn.computeIfAbsent(slot, k -> new ArrayList<>()).add(step);
        }

        synchronized Map<Integer, List<Drawn>> scenario() {
            Map<Integer, List<Drawn>> copy = new java.util.TreeMap<>();
            drawn.forEach((slot, steps) -> copy.put(slot, List.copyOf(steps)));
            return copy;
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
