package se.deversity.asynctest;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCommonPoolBlockingDetector;
import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.telemetry.ControlledSchedule;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

/**
 * The wrappers {@link AgentConcurrencyUtilHooks} hands to executors and {@code CompletableFuture}
 * stages, and the per-thread record of what this thread submitted (#741, #1213).
 *
 * <p>Split out of {@code AgentConcurrencyUtilHooks} so that class holds the woven entry points and
 * this one the hand-off machinery behind them. Nothing here is called from woven bytecode, so none
 * of it is bound by the hooks' {@code @AIContract}; the hooks reach these types by import.
 *
 * <p>The {@code Agent*Hooks} name is load-bearing although nothing here is a hook. {@code
 * SiteCapture} skips the frames of an {@code Agent*Hooks} class and its nested classes, so a
 * finding raised under {@code HandedTask.run()} still names the user's line, not this file; and
 * {@code DetectorFeedCoverageTest} and {@code DetectorConditionalInsertTest} scan only {@code
 * Agent*Hooks.java} sources, so a detector accessor or a registration added here stays in their
 * scope.
 */
final class AgentHandOffHooks {

    private AgentHandOffHooks() {
    }

    /**
     * The futures an {@code allOf} waits for, and the work this thread knew to be behind each. It
     * completes only once they all have, so observing it observes every one of them. Taken when
     * {@code allOf} is called, because a thread remembers only its last {@link Submissions#MAX_KEPT}
     * submissions: a child submitted further back than that, like one this thread did not submit, is
     * observed only through what a woven {@code complete} released to it.
     */
    static final class HandedAll extends Handed {

        private final CompletableFuture<?>[] children;
        private final @Nullable Handed[] work;

        HandedAll(CompletableFuture<?>[] children) {
            this.children = children.clone();
            this.work = new Handed[children.length];
            for (int i = 0; i < children.length; i++) {
                work[i] = Submissions.peek(children[i]);
            }
        }

        void observed() {
            for (int i = 0; i < children.length; i++) {
                AgentConcurrencyUtilHooks.observed(children[i], work[i]);
            }
        }
    }

    /**
     * Work handed to another thread, or to a later moment, with the stamp of the thread that ran
     * it taken when it finished; {@code null} until then. The stamp is what a get or join on the
     * thread that handed it over receives (#741).
     */
    abstract static class Handed {

        /** Where task tokens come from; unique in the JVM, which is what the bridge matches on. */
        static final AtomicLong TOKENS = new AtomicLong();

        volatile HappensBefore.@Nullable Stamp finished;

        /** {@return the stamp the work finished with, {@code null} while it has not} */
        final HappensBefore.@Nullable Stamp finished() {
            return finished;
        }
    }

    /**
     * A submitted task carrying the submitter's clock to the thread that runs it, and that
     * thread's clock back to whoever gets the result (#741).
     *
     * <p>The executor hands the task over in JDK code the agent does not weave, so neither end
     * has an object to name at the moment it happens; the wrapper is that object. It takes the
     * submitter's stamp when it is made, which the running thread receives before the task, and
     * takes the running thread's stamp when the task ends, before the executor completes the
     * future, so a get that returned finds it. It also carries a token the telemetry bridge
     * attributes the task by: the submitter publishes it when the wrapper is made, and the running
     * thread publishes it around the task, so a task a worker submitted is judged as part of the
     * worker's run while it runs, and the pool thread is not before or after (#745). One object of
     * four fields per submission; the stamps are snapshots the threads already hold.
     */
    static final class HandedTask extends Handed implements Runnable, Callable<Object>, Supplier<Object> {

        final Object task;
        private final HappensBefore.Stamp submitted;
        private final long token;
        private final @Nullable AsyncTestContext submittedIn;

        /** The executor a woven submit gave this task to, or {@code null}. */
        @Nullable Object executor;

        /**
         * The executor this task ran on, given by a woven submit or execute, or {@code null}: the
         * task releases into it when it ends, which a woven {@code awaitTermination} or
         * {@code close} that saw the pool terminated acquires (#1266). Apart from
         * {@link #executor}, which a sibling-wait judgement reads and an execute leaves unset.
         */
        private @Nullable Object pool;

        /** Whether the task was handed to the common pool, and declared to the blocking detector. */
        private volatile boolean commonPool;

        /**
         * The controlled schedule's adoption of this task (#1049), taken on the submitting worker,
         * or {@code null} outside a controlled round: the task joins the round where it runs.
         */
        final @Nullable Object adoption;

        HandedTask(Object task) {
            this.task = task;
            this.submitted = HappensBefore.handOff();
            this.token = TOKENS.incrementAndGet();
            this.submittedIn = AsyncTestContext.agentContext();
            this.adoption = ControlledSchedule.submitting();
            TelemetryRegistry.taskSubmitted(token);
        }

        /** {@return this task, remembering the executor it is submitted to} */
        HandedTask onExecutor(Object submittedTo) {
            executor = submittedTo;
            pool = submittedTo;
            return this;
        }

        /** {@return this task, remembering the pool it ends in, for termination only (#1266)} */
        HandedTask endsIn(Object executedBy) {
            pool = executedBy;
            return this;
        }

        /** Tells the controlled schedule this task will never run: its executor rejected it. */
        void abandoned() {
            ControlledSchedule.taskAbandoned(adoption);
        }

        /**
         * Declares this task, submitted to the common {@code ForkJoinPool}, to the detector that
         * reports a blocking call inside one; this wrapper is the identity it is tracked by.
         *
         * @return this task
         */
        HandedTask onTheCommonPool() {
            CompletableFutureCommonPoolBlockingDetector pool =
                    AsyncTestContext.currentCfCommonPoolBlockingDetector();
            if (pool != null) {
                pool.recordCommonPoolSubmission(this, Thread.currentThread(), AgentConcurrencyUtilHooks.callbackLabel(task));
                commonPool = true;
            }
            return this;
        }

        /**
         * Marks this task as the one the calling thread runs, when it is a common-pool task running
         * on a common-pool worker. With parallelism 1 the JDK runs such a task on a thread of its
         * own, which starves nothing, and that thread is not a pool worker.
         *
         * @return whether it marked, so {@link #leavePool} knows to undo it
         */
        @SuppressWarnings("ReferenceEquality") // the common pool, by identity
        private boolean enterPool() {
            if (commonPool && Thread.currentThread() instanceof ForkJoinWorkerThread worker
                    && worker.getPool() == ForkJoinPool.commonPool()) { // NOPMD CompareObjectsWithEquals - the one pool, by identity
                AgentConcurrencyUtilHooks.COMMON_POOL_TASK.set(this);
                return true;
            }
            return false;
        }

        /** Undoes {@link #enterPool}, restoring whatever task the thread was running before. */
        private static void leavePool(boolean entered, @Nullable HandedTask outer) {
            if (!entered) {
                return;
            }
            if (outer == null) {
                AgentConcurrencyUtilHooks.COMMON_POOL_TASK.remove();
            } else {
                AgentConcurrencyUtilHooks.COMMON_POOL_TASK.set(outer);
            }
        }

        @Override
        public void run() {
            Object scheduled = ControlledSchedule.taskStarting(adoption);
            HappensBefore.receive(submitted);
            AsyncTestContext lentBefore = AsyncTestContext.lend(submittedIn);
            TelemetryRegistry.taskStarted(token);
            AgentConcurrencyUtilHooks.mdcAtTaskStart();
            long taskBefore = AgentConcurrencyUtilHooks.enterTask();
            HandedTask outer = AgentConcurrencyUtilHooks.COMMON_POOL_TASK.get();
            boolean entered = enterPool();
            HandedTask outerRunning = AgentConcurrencyUtilHooks.RUNNING_HANDED.get();
            AgentConcurrencyUtilHooks.RUNNING_HANDED.set(this);
            try {
                ((Runnable) task).run();
            } finally {
                AgentConcurrencyUtilHooks.RUNNING_HANDED.set(outerRunning);
                leavePool(entered, outer);
                HappensBefore.release(pool);
                finished = HappensBefore.handOff();
                TelemetryRegistry.taskEnded();
                AgentConcurrencyUtilHooks.leaveTask(taskBefore);
                AgentConcurrencyUtilHooks.mdcAtTaskEnd();
                AsyncTestContext.restoreLent(lentBefore);
                ControlledSchedule.taskEnded(scheduled);
            }
        }

        @Override
        public @Nullable Object call() throws Exception {
            Object scheduled = ControlledSchedule.taskStarting(adoption);
            HappensBefore.receive(submitted);
            AsyncTestContext lentBefore = AsyncTestContext.lend(submittedIn);
            TelemetryRegistry.taskStarted(token);
            AgentConcurrencyUtilHooks.mdcAtTaskStart();
            long taskBefore = AgentConcurrencyUtilHooks.enterTask();
            HandedTask outer = AgentConcurrencyUtilHooks.COMMON_POOL_TASK.get();
            boolean entered = enterPool();
            HandedTask outerRunning = AgentConcurrencyUtilHooks.RUNNING_HANDED.get();
            AgentConcurrencyUtilHooks.RUNNING_HANDED.set(this);
            try {
                return ((Callable<?>) task).call();
            } finally {
                AgentConcurrencyUtilHooks.RUNNING_HANDED.set(outerRunning);
                leavePool(entered, outer);
                HappensBefore.release(pool);
                finished = HappensBefore.handOff();
                TelemetryRegistry.taskEnded();
                AgentConcurrencyUtilHooks.leaveTask(taskBefore);
                AgentConcurrencyUtilHooks.mdcAtTaskEnd();
                AsyncTestContext.restoreLent(lentBefore);
                ControlledSchedule.taskEnded(scheduled);
            }
        }

        @Override
        public @Nullable Object get() {
            Object scheduled = ControlledSchedule.taskStarting(adoption);
            HappensBefore.receive(submitted);
            AsyncTestContext lentBefore = AsyncTestContext.lend(submittedIn);
            TelemetryRegistry.taskStarted(token);
            AgentConcurrencyUtilHooks.mdcAtTaskStart();
            long taskBefore = AgentConcurrencyUtilHooks.enterTask();
            HandedTask outer = AgentConcurrencyUtilHooks.COMMON_POOL_TASK.get();
            boolean entered = enterPool();
            HandedTask outerRunning = AgentConcurrencyUtilHooks.RUNNING_HANDED.get();
            AgentConcurrencyUtilHooks.RUNNING_HANDED.set(this);
            try {
                return ((Supplier<?>) task).get();
            } finally {
                AgentConcurrencyUtilHooks.RUNNING_HANDED.set(outerRunning);
                leavePool(entered, outer);
                HappensBefore.release(pool);
                finished = HappensBefore.handOff();
                TelemetryRegistry.taskEnded();
                AgentConcurrencyUtilHooks.leaveTask(taskBefore);
                AgentConcurrencyUtilHooks.mdcAtTaskEnd();
                AsyncTestContext.restoreLent(lentBefore);
                ControlledSchedule.taskEnded(scheduled);
            }
        }

        /** The task's own, so a future that prints its task prints the caller's. */
        @Override
        public String toString() {
            return task.toString();
        }
    }

    /**
     * A function registered on a {@code CompletableFuture}, carrying the registering thread's clock
     * and the completions it follows to whichever thread runs it, and that thread's clock back to a
     * join on the registering thread (#741).
     *
     * <p>The JDK runs a dependent stage's function on the thread that completes its source, on an
     * executor for the async forms, or inside the registering call when the source is already
     * complete, all in code the agent does not weave. The wrapper is never handed back: a
     * {@code CompletableFuture} keeps its functions to itself. It acquires what a woven
     * {@code complete} released to each source it waits for, and what the task behind a source
     * this thread submitted published when it finished. A stage that follows either of two sources
     * acquires neither, since it is not known which one it followed. Like {@link HandedTask} it
     * carries a task token, so a pool thread running a stage the body registered is attributed to
     * the run while it runs (#834).
     */
    abstract static class HandedStage extends Handed {

        private final HappensBefore.Stamp registered;
        private final @Nullable Object source;
        private final @Nullable Object other;
        private final @Nullable Handed sourceWork;
        private final @Nullable Handed otherWork;
        private final long token;
        private final @Nullable AsyncTestContext registeredIn;

        /**
         * The {@code CompletableFuture} the function returned, for {@code thenCompose} and
         * {@code exceptionallyCompose}: the stage completes when that one does (#741).
         */
        volatile @Nullable Object composed;

        /**
         * The work behind {@link #composed}, when the function's thread submitted or registered
         * it: an inner {@code supplyAsync} task or stage completes its future in JDK code, with
         * no woven {@code complete} to release anything, so what it did reaches a later observer
         * only through this. {@link Submissions} is per thread, so it is looked up on the thread
         * that ran the function, and written before {@link #composed}.
         */
        volatile @Nullable Handed composedWork;

        /**
         * Orders the calling thread after the stage {@code work}'s function returned, when it is
         * a composing stage: the stage completed when that inner one did.
         */
        static void followComposed(@Nullable Handed work) {
            if (work instanceof HandedStage stage) {
                Object inner = stage.composed;
                if (inner != null) {
                    AgentConcurrencyUtilHooks.observed(inner, stage.composedWork);
                }
            }
        }

        /**
         * Takes the registering thread's clock, on that thread.
         *
         * @param source the stage the function follows, {@code null} when that is not known
         * @param other  the second stage it also follows, {@code null} for none or not known
         */
        HandedStage(@Nullable Object source, @Nullable Object other) {
            this.registered = HappensBefore.handOff();
            this.source = source;
            this.other = other;
            this.sourceWork = source == null ? null : Submissions.peek(source);
            this.otherWork = other == null ? null : Submissions.peek(other);
            this.token = TOKENS.incrementAndGet();
            this.registeredIn = AsyncTestContext.agentContext();
            TelemetryRegistry.taskSubmitted(token);
        }

        /**
         * Orders the thread about to run the function after everything it follows, and lends it
         * the registering thread's run (#834).
         *
         * @return what the thread was lent before, for {@link #end}
         */
        final @Nullable AsyncTestContext begin() {
            HappensBefore.receive(registered);
            if (source != null) {
                HappensBefore.acquire(source);
            }
            if (other != null) {
                HappensBefore.acquire(other);
            }
            if (sourceWork instanceof HandedAll all) {
                all.observed();
            } else if (sourceWork != null) {
                HappensBefore.receive(sourceWork.finished());
            }
            followComposed(sourceWork);
            if (otherWork instanceof HandedAll all) {
                all.observed();
            } else if (otherWork != null) {
                HappensBefore.receive(otherWork.finished());
            }
            followComposed(otherWork);
            AsyncTestContext lentBefore = AsyncTestContext.lend(registeredIn);
            TelemetryRegistry.taskStarted(token);
            // Inside the loan, so a callback a pool thread runs for the run is the run's.
            CompletableFutureBlockingCallbackDetector callbacks =
                    AsyncTestContext.currentCfBlockingCallbackDetector();
            if (callbacks != null) {
                callbacks.recordEnterCallback(AgentConcurrencyUtilHooks.callbackLabel(callback()), Thread.currentThread());
            }
            return lentBefore;
        }

        /** {@return the caller's function this stage runs} */
        abstract Object callback();

        /**
         * Takes the clock of the thread that ran the function, for a join of the stage, and ends
         * the loan {@link #begin} made.
         *
         * @param lentBefore what {@link #begin} returned
         */
        final void end(@Nullable AsyncTestContext lentBefore) {
            CompletableFutureBlockingCallbackDetector callbacks =
                    AsyncTestContext.currentCfBlockingCallbackDetector();
            if (callbacks != null) {
                callbacks.recordExitCallback(Thread.currentThread());
            }
            finished = HappensBefore.handOff();
            TelemetryRegistry.taskEnded();
            AsyncTestContext.restoreLent(lentBefore);
        }
    }

    /** A {@link HandedStage} for every function shape but the two-argument function. */
    static final class StageFunction extends HandedStage
            implements Function<Object, Object>, Consumer<Object>, BiConsumer<Object, Object>, Runnable {

        private final Object function;

        StageFunction(Object function, @Nullable Object source, @Nullable Object other) {
            super(source, other);
            this.function = function;
        }

        @Override
        Object callback() {
            return function;
        }

        @Override
        @SuppressWarnings("unchecked") // the caller's own function, erased at the call site
        public @Nullable Object apply(@Nullable Object value) {
            AsyncTestContext lentBefore = begin();
            try {
                Object result = ((Function<@Nullable Object, @Nullable Object>) function).apply(value);
                if (result instanceof CompletableFuture) {
                    composedWork = Submissions.peek(result);
                    composed = result;
                }
                return result;
            } finally {
                end(lentBefore);
            }
        }

        @Override
        @SuppressWarnings("unchecked") // the caller's own action, erased at the call site
        public void accept(@Nullable Object value) {
            AsyncTestContext lentBefore = begin();
            try {
                ((Consumer<@Nullable Object>) function).accept(value);
            } finally {
                end(lentBefore);
            }
        }

        @Override
        @SuppressWarnings("unchecked") // the caller's own action, erased at the call site
        public void accept(@Nullable Object value, @Nullable Object failure) {
            AsyncTestContext lentBefore = begin();
            try {
                ((BiConsumer<@Nullable Object, @Nullable Object>) function).accept(value, failure);
            } finally {
                end(lentBefore);
            }
        }

        @Override
        public void run() {
            AsyncTestContext lentBefore = begin();
            try {
                ((Runnable) function).run();
            } finally {
                end(lentBefore);
            }
        }

        /** {@return this function as {@code thenCompose} takes it} */
        @SuppressWarnings("unchecked") // apply returns what the caller's function returned
        Function<Object, CompletionStage<Object>> composing() {
            return (Function<Object, CompletionStage<Object>>) (Function<?, ?>) this;
        }

        /** {@return this function as {@code exceptionally} takes it} */
        @SuppressWarnings("unchecked") // apply hands the failure to the caller's function unchanged
        Function<Throwable, Object> recovering() {
            return (Function<Throwable, Object>) (Function<?, ?>) this;
        }

        /** {@return this function as {@code exceptionallyCompose} takes it} */
        @SuppressWarnings("unchecked") // as composing and recovering together
        Function<Throwable, CompletionStage<Object>> recoveringBy() {
            return (Function<Throwable, CompletionStage<Object>>) (Function<?, ?>) this;
        }

        /** The function's own, so a stage that prints its function prints the caller's. */
        @Override
        public String toString() {
            return function.toString();
        }
    }

    /** A {@link HandedStage} for a two-argument function, {@code thenCombine} and {@code handle}. */
    static final class StageBiFunction extends HandedStage implements BiFunction<Object, Object, Object> {

        private final Object function;

        StageBiFunction(Object function, @Nullable Object source, @Nullable Object other) {
            super(source, other);
            this.function = function;
        }

        @Override
        Object callback() {
            return function;
        }

        @Override
        @SuppressWarnings("unchecked") // the caller's own function, erased at the call site
        public @Nullable Object apply(@Nullable Object value, @Nullable Object second) {
            AsyncTestContext lentBefore = begin();
            try {
                return ((BiFunction<@Nullable Object, @Nullable Object, @Nullable Object>) function)
                        .apply(value, second);
            } finally {
                end(lentBefore);
            }
        }

        /** {@return this function as {@code handle} takes it} */
        @SuppressWarnings("unchecked") // apply hands the failure to the caller's function unchanged
        BiFunction<Object, Throwable, Object> handling() {
            return (BiFunction<Object, Throwable, Object>) (BiFunction<?, ?, ?>) this;
        }

        /** The function's own, so a stage that prints its function prints the caller's. */
        @Override
        public String toString() {
            return function.toString();
        }
    }

    /**
     * The futures this thread submitted most recently, each with its {@link HandedTask} or
     * {@link HandedStage}, so a
     * get on this thread can find what the task published (#741).
     *
     * <p>A future the JDK returns cannot carry the task's clock, and a map keyed by every future
     * would cost an entry per submission. What a get needs is almost always a future its own
     * thread submitted, so each thread keeps its last {@link #KEPT} and a get forgets the one it
     * found. A get on another thread, or of a future pushed out by later submissions, finds
     * nothing and orders nothing, which is the answer the model had before.
     */
    static final class Submissions {

        /** How many submissions a thread starts out able to remember. */
        private static final int INITIAL_KEPT = 16;

        /**
         * The most a thread remembers. A thread that submits, then gets each future, then reads the
         * results is correct however many it submitted, and anything forgotten reports it (#1176). It
         * grows on demand, so a thread that submits a few tasks pays for sixteen slots as before.
         */
        static final int MAX_KEPT = 1024;

        private static final ThreadLocal<Submissions> MINE = ThreadLocal.withInitial(Submissions::new);

        private @Nullable Object[] futures = new Object[INITIAL_KEPT];
        private @Nullable Handed[] tasks = new Handed[INITIAL_KEPT];
        private int next;

        /** {@return {@code future}, remembered on this thread with the task behind it} */
        static <F> F remember(F future, Handed task) {
            Submissions mine = MINE.get();
            if (mine.next == mine.futures.length && mine.futures.length < MAX_KEPT) {
                mine.futures = Arrays.copyOf(mine.futures, mine.futures.length * 2);
                mine.tasks = Arrays.copyOf(mine.tasks, mine.tasks.length * 2);
            }
            if (mine.next == mine.futures.length) {
                mine.next = 0; // full at its largest: the oldest goes
            }
            mine.futures[mine.next] = future;
            mine.tasks[mine.next] = task;
            mine.next++;
            return future;
        }

        /** {@return the task behind {@code future}, forgotten, or {@code null} if not remembered} */
        @SuppressWarnings("ReferenceEquality") // the same future, not an equal one
        static @Nullable Handed forget(Object future) {
            Submissions mine = MINE.get();
            for (int i = 0; i < mine.futures.length; i++) {
                if (mine.futures[i] == future) { // NOPMD CompareObjectsWithEquals - identity
                    Handed task = mine.tasks[i];
                    mine.futures[i] = null;
                    mine.tasks[i] = null;
                    return task;
                }
            }
            return null;
        }

        /**
         * {@return the work behind {@code future}, still remembered, or {@code null}}: a stage
         * registered on it needs what the work published without taking it from a later join.
         */
        @SuppressWarnings("ReferenceEquality") // the same future, not an equal one
        static @Nullable Handed peek(Object future) {
            Submissions mine = MINE.get();
            for (int i = 0; i < mine.futures.length; i++) {
                if (mine.futures[i] == future) { // NOPMD CompareObjectsWithEquals - identity
                    return mine.tasks[i];
                }
            }
            return null;
        }
    }
}
